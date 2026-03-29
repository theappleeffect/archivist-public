package com.archivist.detection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the server brand string to identify server software and version.
 */
public final class BrandDetector {

    private static final Pattern BRAND_PATTERN = Pattern.compile(
            "(Paper|Spigot|Purpur|Velocity|BungeeCord|Waterfall|Folia|Pufferfish)[\\s/-]?([\\d.]+)?"
    );

    /**
     * Parses a raw server brand string to extract server software and version.
     *
     * @param rawBrand the raw brand string from the server
     * @return the parsed result with software name and optional version
     */
    public BrandResult parseBrand(String rawBrand) {
        if (rawBrand == null || rawBrand.isEmpty()) {
            return new BrandResult(rawBrand, null, null);
        }

        Matcher matcher = BRAND_PATTERN.matcher(rawBrand);
        if (matcher.find()) {
            String software = matcher.group(1);
            String version = matcher.group(2);
            return new BrandResult(rawBrand, software, version);
        }

        return new BrandResult(rawBrand, null, null);
    }

    /**
     * Result of parsing a server brand string.
     *
     * @param rawBrand        the original brand string
     * @param serverSoftware  the detected server software name, or null
     * @param softwareVersion the detected software version, or null
     */
    public record BrandResult(
            String rawBrand,
            String serverSoftware,
            String softwareVersion
    ) {}
}
