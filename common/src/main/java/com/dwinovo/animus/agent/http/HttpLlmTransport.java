package com.dwinovo.animus.agent.http;

import com.dwinovo.animus.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * HTTPS transport for OpenAI-protocol chat completions. Built on the JDK
 * {@code java.net.http.HttpClient} (Java 11+, daemon-threaded executor) so
 * we ship zero third-party HTTP dependencies.
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li>{@link #post}    — buffered: response JSON returned whole.
 *       Used historically; useful for non-streaming backends or debug.</li>
 *   <li>{@link #postSse} — streamed: caller passes a chunk-handler invoked
 *       once per parsed SSE event. Backend must respond with
 *       {@code text/event-stream}.</li>
 * </ul>
 *
 * <h2>Request id tagging</h2>
 * Every call gets a short sequential id ({@code lr-N}) logged on send, on
 * response status, and on each streamed chunk — makes it possible to follow
 * one specific request through the log when the agent loop has interleaved
 * activity from multiple Animuses or back-to-back retries.
 *
 * <h2>Error model</h2>
 * <ul>
 *   <li>2xx + streaming → caller's chunkHandler invoked, future completes
 *       when stream terminates (graceful {@code [DONE]} or stream close)</li>
 *   <li>non-2xx → future fails with {@link LlmHttpException} carrying the
 *       status code and full response body</li>
 *   <li>network / DNS / timeout → future fails with the wrapped IOException</li>
 * </ul>
 */
public final class HttpLlmTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final AtomicInteger REQUEST_ID_SOURCE = new AtomicInteger();

    private final HttpClient client;

    public HttpLlmTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * POST a JSON body, get the whole JSON response back. Used for
     * non-streaming requests.
     */
    public CompletableFuture<JsonObject> post(String url, String apiKey, JsonObject body) {
        String requestId = nextRequestId();
        String bodyStr = body.toString();
        long t0 = System.nanoTime();
        Constants.LOG.debug("[animus-http][{}] POST {} ({} bytes, buffered)",
                requestId, url, bodyStr.length());

        HttpRequest request = baseRequest(url, apiKey, "application/json", bodyStr);
        return client.sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> interpretBuffered(requestId, t0, resp));
    }

    /**
     * POST a JSON body, stream the response as SSE events into the
     * {@code chunkHandler}. The returned future completes when the stream
     * terminates normally; it fails with {@link LlmHttpException} if the
     * server replied non-2xx (in which case the chunk handler is never
     * invoked).
     */
    public CompletableFuture<Void> postSse(String url, String apiKey, JsonObject body,
                                            Consumer<JsonObject> chunkHandler) {
        String requestId = nextRequestId();
        String bodyStr = body.toString();
        long t0 = System.nanoTime();
        AtomicLong chunkCount = new AtomicLong();
        Constants.LOG.debug("[animus-http][{}] POST {} ({} bytes, streaming)",
                requestId, url, bodyStr.length());

        HttpRequest request = baseRequest(url, apiKey, "text/event-stream", bodyStr);

        // Branch on status: 2xx → SSE subscriber; non-2xx → buffer to string so
        // we can surface the (typically JSON) error body in LlmHttpException.
        // Uniform String result: streaming branch returns the "" sentinel,
        // error branch returns the actual body. The downstream continuation
        // decides which path applied based on status code.
        BodyHandler<String> handler = ri -> {
            if (ri.statusCode() / 100 == 2) {
                SseSubscriber sub = new SseSubscriber(requestId, chunkHandler, chunkCount);
                // 4-arg overload: subscriber, finisher → String, charset, line separator
                return BodySubscribers.fromLineSubscriber(sub, s -> "", StandardCharsets.UTF_8, "\n");
            }
            return BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        return client.sendAsync(request, handler).thenCompose(resp -> {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            int status = resp.statusCode();
            if (status / 100 != 2) {
                String body2 = resp.body() == null ? "" : resp.body();
                Constants.LOG.warn("[animus-http][{}] ✗ {} in {}ms — body: {}",
                        requestId, status, elapsedMs, truncate(body2, 500));
                return CompletableFuture.failedFuture(new LlmHttpException(status, body2));
            }
            Constants.LOG.debug("[animus-http][{}] ✓ {} in {}ms, {} chunks",
                    requestId, status, elapsedMs, chunkCount.get());
            return CompletableFuture.completedFuture((Void) null);
        });
    }

    // ---- internals ----

    private HttpRequest baseRequest(String url, String apiKey, String accept, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static CompletableFuture<JsonObject> interpretBuffered(String requestId, long t0,
                                                                    HttpResponse<String> resp) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (status / 100 != 2) {
            Constants.LOG.warn("[animus-http][{}] ✗ {} in {}ms — body: {}",
                    requestId, status, elapsedMs, truncate(body, 500));
            return CompletableFuture.failedFuture(new LlmHttpException(status, body));
        }
        Constants.LOG.debug("[animus-http][{}] ✓ {} in {}ms ({} bytes)",
                requestId, status, elapsedMs, body.length());
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return CompletableFuture.completedFuture(obj);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(
                    new LlmHttpException(status, "response is not a JSON object: " + ex.getMessage()
                            + "; body: " + body));
        }
    }

    private static String nextRequestId() {
        return "lr-" + REQUEST_ID_SOURCE.incrementAndGet();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Server-Sent Events subscriber. Buffers data lines per event (events
     * are blank-line separated, lines start with {@code data: }), parses
     * each completed event as JSON, and feeds it to the chunk handler.
     *
     * <h2>Multiline data handling</h2>
     * SSE spec allows multiple {@code data:} lines per event (concatenated
     * with {@code \n} between them when the event fires). OpenAI / DeepSeek
     * use single-line data exclusively, but we handle multiline for spec
     * compliance.
     *
     * <h2>{@code [DONE]} sentinel</h2>
     * OpenAI's stream terminates with {@code data: [DONE]\n\n}; we
     * specifically skip parsing that as JSON.
     */
    private static final class SseSubscriber implements Flow.Subscriber<String> {

        private final String requestId;
        private final Consumer<JsonObject> handler;
        private final AtomicLong chunkCount;
        private final StringBuilder buffer = new StringBuilder();
        private Flow.Subscription subscription;

        SseSubscriber(String requestId, Consumer<JsonObject> handler, AtomicLong chunkCount) {
            this.requestId = requestId;
            this.handler = handler;
            this.chunkCount = chunkCount;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            if (line.isEmpty()) {
                flushEvent();
            } else if (line.startsWith("data: ")) {
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 6, line.length());
            } else if (line.startsWith("data:")) {
                // Spec-permissive: "data:" without trailing space is valid too.
                if (buffer.length() > 0) buffer.append('\n');
                buffer.append(line, 5, line.length());
            }
            // Other SSE fields (event:, id:, retry:) ignored — we don't need them.
        }

        @Override
        public void onError(Throwable t) {
            Constants.LOG.warn("[animus-http][{}] SSE stream error: {}",
                    requestId, t.getClass().getSimpleName() + ": " + t.getMessage());
            // Future will fail via the wrapping CompletableFuture.
        }

        @Override
        public void onComplete() {
            flushEvent();
        }

        private void flushEvent() {
            if (buffer.length() == 0) return;
            String data = buffer.toString();
            buffer.setLength(0);
            if ("[DONE]".equals(data)) return;
            try {
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                chunkCount.incrementAndGet();
                handler.accept(obj);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[animus-http][{}] ignoring malformed SSE chunk: {} (data: {})",
                        requestId, ex.getMessage(), truncate(data, 200));
            }
        }
    }
}
