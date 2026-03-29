package com.archivist.detection.fingerprint.model;

/**
 * Result of matching a GUI capture against a rule pattern.
 */
public record GuiRuleMatch(
        GuiRule rule,
        GuiRule.GuiPattern pattern,
        int matchedMarkers,
        int totalMarkers,
        double confidence
) {}
