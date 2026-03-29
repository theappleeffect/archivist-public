package com.archivist.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Lightweight HTTP client wrapping {@link java.net.http.HttpClient}.
 * All methods are synchronous and should be called from a background thread
 * (via {@link com.archivist.util.ArchivistExecutor}).
 *
 * <p>No hardcoded URLs. All endpoints come from caller parameters.</p>
 */
public final class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;

    public ApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Sends a POST request with a JSON body.
     */
    public ApiResponse post(String url, String json, Map<String, String> headers) {
        return sendJson("POST", url, json, headers);
    }

    /**
     * Sends a PUT request with a JSON body.
     */
    public ApiResponse put(String url, String json, Map<String, String> headers) {
        return sendJson("PUT", url, json, headers);
    }

    /**
     * Sends a PATCH request with a JSON body.
     */
    public ApiResponse patch(String url, String json, Map<String, String> headers) {
        return sendJson("PATCH", url, json, headers);
    }

    /**
     * Sends a GET request.
     */
    public ApiResponse get(String url, Map<String, String> headers) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null) {
                return ApiResponse.error("Invalid URL (missing http:// or https://): " + url);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            applyHeaders(builder, headers);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            return ApiResponse.of(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResponse.error("Request interrupted");
        } catch (Exception e) {
            LOGGER.warn("[Archivist] GET {} failed: {}", url, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Returns true if the URL uses HTTP (not HTTPS) and is not localhost.
     */
    /**
     * Returns true if the URL points to a private/internal address (SSRF risk).
     */
    public static boolean isInternalUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return true;
            host = host.toLowerCase();
            return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]")
                    || host.startsWith("10.") || host.startsWith("192.168.")
                    || host.startsWith("172.16.") || host.startsWith("172.17.")
                    || host.startsWith("172.18.") || host.startsWith("172.19.")
                    || host.startsWith("172.2") || host.startsWith("172.3")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || host.equals("metadata.google.internal")
                    || host.equals("169.254.169.254");
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isInsecureUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://")) return false;
        // Allow localhost
        return !lower.startsWith("http://localhost") && !lower.startsWith("http://127.0.0.1")
                && !lower.startsWith("http://[::1]");
    }

    private ApiResponse sendJson(String method, String url, String json, Map<String, String> headers) {
        if (isInternalUrl(url)) {
            return ApiResponse.error("Blocked: URL points to internal/private address");
        }
        if (isInsecureUrl(url)) {
            LOGGER.warn("[Archivist] WARNING: Sending data to non-HTTPS endpoint: {}", url);
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null) {
                return ApiResponse.error("Invalid URL (missing http:// or https://): " + url);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
            applyHeaders(builder, headers);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            return ApiResponse.of(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResponse.error("Request interrupted");
        } catch (Exception e) {
            LOGGER.warn("[Archivist] {} {} failed: {}", method, url, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                builder.header(key, value);
            }
        }
    }
}
