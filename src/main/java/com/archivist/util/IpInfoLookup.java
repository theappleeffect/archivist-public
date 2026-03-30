package com.archivist.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class IpInfoLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;

    public IpInfoLookup() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record IpInfoResult(
            String queriedIp,
            String rawDomain,
            String resolvedIp,
            String ipInfoJson,
            String error
    ) {
        public boolean isSuccess() {
            return error == null && ipInfoJson != null;
        }
    }

    public IpInfoResult lookup(String address) {
        if (address == null || address.isBlank()) {
            return new IpInfoResult(null, null, null, null, "Address cannot be empty");
        }

        String rawDomain = stripPort(address);
        String resolvedIp;

        try {
            InetAddress inet = InetAddress.getByName(rawDomain);
            resolvedIp = inet.getHostAddress();
        } catch (Exception e) {
            LOGGER.warn("[Archivist] DNS lookup failed for {}: {}", rawDomain, e.getMessage());
            return new IpInfoResult(null, rawDomain, null, null, "DNS lookup failed: " + e.getMessage());
        }

        if (resolvedIp == null || resolvedIp.isBlank()) {
            return new IpInfoResult(null, rawDomain, null, null, "Could not resolve IP for domain");
        }

        String json = fetchIpInfo(resolvedIp);
        if (json == null) {
            return new IpInfoResult(resolvedIp, rawDomain, null, null, "Failed to fetch ipinfo.io data");
        }

        return new IpInfoResult(resolvedIp, rawDomain, resolvedIp, json, null);
    }

    private String stripPort(String address) {
        if (address == null) return "";
        String trimmed = address.trim();
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon > 0) {
            String beforeColon = trimmed.substring(0, lastColon);
            if (beforeColon.contains(".") || beforeColon.equals("localhost")) {
                return beforeColon;
            }
        }
        return trimmed;
    }

    private String fetchIpInfo(String ip) {
        try {
            URI uri = URI.create("https://ipinfo.io/" + ip);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "curl/8.1.2")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOGGER.warn("[Archivist] ipinfo.io returned status {} for IP {}", response.statusCode(), ip);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.warn("[Archivist] Failed to fetch ipinfo.io for {}: {}", ip, e.getMessage());
            return null;
        }
    }
}
