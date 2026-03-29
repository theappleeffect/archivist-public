package com.archivist.detection;

/**
 * A single detection result with confidence metadata.
 *
 * @param value      the detected value (plugin name, brand string, URL, etc.)
 * @param type       the detection method that produced this result
 * @param confidence raw confidence 0.0–1.0 from the detector (before session adjustment)
 * @param source     human-readable source info (e.g. "command: worldguard:region")
 */
public record Detection(
        String value,
        DetectionType type,
        double confidence,
        String source
) {
    /**
     * Create a detection using the type's default confidence.
     */
    public static Detection of(String value, DetectionType type, String source) {
        return new Detection(value, type, type.defaultConfidence(), source);
    }

    /**
     * Create a detection with explicit confidence.
     */
    public static Detection of(String value, DetectionType type, double confidence, String source) {
        return new Detection(value, type, confidence, source);
    }

    /**
     * Merge two confidences for the same detection value (different methods).
     * Uses the "at least one correct" formula: 1 - (1-c1)(1-c2)
     */
    public static double mergeConfidence(double c1, double c2) {
        return 1.0 - (1.0 - c1) * (1.0 - c2);
    }
}
