package com.localcloud.sync.adapters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;

/**
 * HTTP client with exponential backoff retry on 429 (rate limit) responses.
 * Backoff: 2s -> 4s -> 8s -> 16s -> 30s cap, max 5 retries.
 */
public class RetryableHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RetryableHttpClient.class);
    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    public record HttpResult(int statusCode, String body) {}

    public HttpResult get(String url, String accessToken) throws IOException {
        return execute("GET", url, null, accessToken);
    }

    public HttpResult post(String url, String body, String accessToken) throws IOException {
        return execute("POST", url, body, accessToken);
    }

    /** For local emulator calls (no auth, no retry). */
    public HttpResult localPost(String url, String body) throws IOException {
        HttpURLConnection conn = openConnection(url, "POST", null);
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return readResponse(conn);
    }

    private HttpResult execute(String method, String url, String body, String accessToken) throws IOException {
        long backoff = INITIAL_BACKOFF_MS;
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpURLConnection conn = openConnection(url, method, accessToken);

                if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                }

                HttpResult result = readResponse(conn);

                if (result.statusCode() == 429) {
                    if (attempt < MAX_RETRIES) {
                        logger.warn("[RETRY] 429 rate limited on {} {} (attempt {}/{}), backing off {}ms",
                                method, url, attempt + 1, MAX_RETRIES, backoff);
                        try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during backoff");
                        }
                        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                        continue;
                    }
                    logger.error("[RETRY] 429 rate limited, exhausted {} retries for {} {}", MAX_RETRIES, method, url);
                }

                return result;

            } catch (NonRetryableException e) {
                // Auth errors (401/403) — fail immediately, no retry
                throw e;
            } catch (SSLException e) {
                // SSL/TLS errors (cert validation, handshake) — fail immediately
                throw new NonRetryableException("SSL error: " + e.getMessage());
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    logger.warn("[RETRY] IO error on {} {} (attempt {}): {}", method, url, attempt + 1, e.getMessage());
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during backoff");
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Request failed after retries");
    }

    private HttpURLConnection openConnection(String url, String method, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        if (accessToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        return conn;
    }

    private HttpResult readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        java.io.InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        conn.disconnect();
        if (status == 401 || status == 403) {
            // Auth errors are not retryable — fail immediately
            throw new NonRetryableException("HTTP " + status + ": " + body);
        }
        if (status >= 400 && status != 429) {
            throw new IOException("HTTP " + status + ": " + body);
        }
        return new HttpResult(status, body);
    }

    /** Thrown for errors that should not be retried (auth failures, etc). */
    public static class NonRetryableException extends IOException {
        public NonRetryableException(String message) { super(message); }
    }
}
