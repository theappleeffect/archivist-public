package com.archivist.detection;

/**
 * The method by which something was detected.
 */
public enum DetectionType {
    COMMAND_TREE_NAMESPACED(0.95),
    COMMAND_TREE_GLOSSARY(0.70),
    TAB_COMPLETE(0.85),
    CHANNEL(0.80),
    REGISTRY(0.65),
    BRAND(0.90),
    CHAT_URL(0.55),
    FINGERPRINT(0.0),  // confidence set per-match
    HEURISTIC(0.45),
    MANUAL(1.0);

    private final double defaultConfidence;

    DetectionType(double defaultConfidence) {
        this.defaultConfidence = defaultConfidence;
    }

    public double defaultConfidence() { return defaultConfidence; }
}
