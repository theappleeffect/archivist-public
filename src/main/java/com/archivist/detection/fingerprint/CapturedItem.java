package com.archivist.detection.fingerprint;

import java.util.List;

/**
 * A single item captured from a server GUI inventory.
 * Unified model replacing ScrapedItem and GuiItemData.
 */
public record CapturedItem(
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
