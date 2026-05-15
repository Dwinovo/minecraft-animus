package com.dwinovo.animus.agent.http;

import com.dwinovo.animus.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal HTTPS transport for OpenAI-protocol chat completions. Built on the
 * JDK {@code java.net.http.HttpClient} (Java 11+, daemon-threaded executor
 * by default — won't block JVM shutdown) so we ship **zero third-party HTTP
 * dependencies**: no OkHttp, no Kotlin stdlib, no Okio.
 *
 * <h2>Why JDK HttpClient</h2>
 * <ul>
 *   <li>Built into the JDK we ship with (Java 25) — no jar bloat</li>
 *   <li>Native async via {@link CompletableFuture} — no Callback↔Future
 *       wrapping like OkHttp</li>
 *   <li>HTTP/2 by default with graceful HTTP/1.1 fallback</li>
 *   <li>TouhouLittleMaid uses this exact API in production for LLM calls,
 *       so the pattern is battle-tested in the MC mod context</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * The completion runs on the HttpClient's internal executor (a daemon
 * cached-thread pool). Consumers are responsible for hopping back to the
 * client thread (or server thread, in case of single-player) before touching
 * world state — see {@code ClientAgentLoop} for the standard pattern.
 *
 * <h2>Error model</h2>
 * <ul>
 *   <li>2xx → future completes with the parsed JSON response body</li>
 *   <li>non-2xx → future fails with {@link LlmHttpException} carrying the
 *       status code and raw body (so DeepSeek's "reasoning_content must be
 *       passed back" prose survives the boundary intact)</li>
 *   <li>network / DNS / timeout → future fails with the underlying
 *       {@link java.io.IOException} (or its async wrapper)</li>
 * </ul>
 */
public final class HttpLlmTransport {

    /** How long to wait for a TCP / TLS handshake. Short — failed handshakes shouldn't hang the UI. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Per-request timeout. Generous — reasoning-mode responses can take 60s+. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final HttpClient client;

    public HttpLlmTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * POST a JSON body to {@code url} with bearer-token auth. Returns the
     * decoded response body as a {@link JsonObject} on success.
     *
     * <p>{@code url} should be the full endpoint URL ({@code baseUrl + provider.chatPath()}
     * resolution is the caller's responsibility — keeps this transport
     * provider-agnostic).
     */
    public CompletableFuture<JsonObject> post(String url, String apiKey, JsonObject body) {
        String bodyStr = body.toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();

        Constants.LOG.debug("[animus-http] POST {} ({} bytes)", url, bodyStr.length());

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(HttpLlmTransport::interpret);
    }

    private static CompletableFuture<JsonObject> interpret(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (status / 100 != 2) {
            return CompletableFuture.failedFuture(new LlmHttpException(status, body));
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            return CompletableFuture.completedFuture(obj);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(
                    new LlmHttpException(status, "response is not a JSON object: " + ex.getMessage()
                            + "; body: " + body));
        }
    }
}
