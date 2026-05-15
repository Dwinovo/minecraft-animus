package com.dwinovo.animus.agent.http;

/**
 * Non-2xx HTTP response from an LLM endpoint. Carries both the status code
 * (for branching: 401 vs 429 vs 5xx) and the raw response body (for the
 * specific provider error message — DeepSeek 400s in particular contain
 * the actual problem in plain prose).
 *
 * <p>{@link IOException}-level failures (DNS, connection refused, TLS) are
 * surfaced via the wrapped {@link java.io.IOException} on the future, not
 * this class.
 */
public final class LlmHttpException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public LlmHttpException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + truncate(responseBody, 500));
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }

    public boolean isRateLimited()    { return statusCode == 429; }
    public boolean isUnauthorized()   { return statusCode == 401 || statusCode == 403; }
    public boolean isClientError()    { return statusCode >= 400 && statusCode < 500; }
    public boolean isServerError()    { return statusCode >= 500; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "... (truncated)";
    }
}
