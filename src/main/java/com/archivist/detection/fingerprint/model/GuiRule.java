package com.archivist.detection.fingerprint.model;

import java.util.List;

/**
 * A plugin detection rule based on GUI inventory markers.
 * Each rule identifies one plugin and contains multiple GUI patterns.
 */
public record GuiRule(
        String pluginId,
        String pluginName,
        List<GuiPattern> patterns
) {
    /**
     * A single GUI pattern that, when matched, indicates the plugin is present.
     */
    public record GuiPattern(
            String label,
            String titleContains,
            double weight,
            List<SlotMarker> markers
    ) {}

    /**
     * A marker for a specific slot in a GUI inventory.
     * All non-null fields must match for the marker to succeed.
     */
    public record SlotMarker(
            int slot,
            String materialId,
            String displayName,
            String displayNameJson,
            List<String> lore,
            List<String> loreJson,
            int count,
            int customModelData,
            boolean hasEnchantGlint
    ) {}
}
