package com.archivist.detection.fingerprint;

import com.archivist.detection.fingerprint.model.GuiRule;
import com.archivist.detection.fingerprint.model.GuiRuleMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Matches GUI captures against user-defined rules.
 * Each rule pattern requires ALL markers to match at their exact slots.
 */
public class GuiRuleMatcher {

    public List<GuiRuleMatch> match(GuiCapture capture, List<GuiRule> rules) {
        List<GuiRuleMatch> results = new ArrayList<>();

        for (GuiRule rule : rules) {
            for (GuiRule.GuiPattern pattern : rule.patterns()) {
                if (pattern.markers() == null || pattern.markers().isEmpty()) continue;

                // Title filter
                if (pattern.titleContains() != null && !pattern.titleContains().isEmpty()) {
                    if (!capture.title().toLowerCase().contains(pattern.titleContains().toLowerCase())) {
                        continue;
                    }
                }

                // Check each marker
                int matched = 0;
                int total = pattern.markers().size();

                for (GuiRule.SlotMarker marker : pattern.markers()) {
                    if (matchesMarker(marker, capture.items())) {
                        matched++;
                    }
                }

                // All markers must match
                if (matched == total) {
                    double confidence = pattern.weight();
                    results.add(new GuiRuleMatch(rule, pattern, matched, total, confidence));
                }
            }
        }

        results.sort(Comparator.comparingDouble(GuiRuleMatch::confidence).reversed());
        return results;
    }

    private boolean matchesMarker(GuiRule.SlotMarker marker, List<CapturedItem> items) {
        // Find item at exact slot
        CapturedItem item = null;
        for (CapturedItem ci : items) {
            if (ci.slot() == marker.slot()) {
                item = ci;
                break;
            }
        }
        if (item == null) return false;

        // Material ID
        if (marker.materialId() != null && !marker.materialId().isEmpty()) {
            if (!marker.materialId().equals(item.materialId())) return false;
        }

        // Display name — prefer JSON (exact color match) if available, fall back to plain text
        if (marker.displayNameJson() != null && !marker.displayNameJson().isEmpty()
                && item.displayNameJson() != null && !item.displayNameJson().isEmpty()) {
            if (!marker.displayNameJson().equals(item.displayNameJson())) return false;
        } else if (marker.displayName() != null && !marker.displayName().isEmpty()) {
            if (!marker.displayName().equals(item.displayName())) return false;
        }

        // Lore — prefer JSON if available
        if (marker.loreJson() != null && !marker.loreJson().isEmpty()) {
            if (item.loreJson() == null || item.loreJson().size() < marker.loreJson().size()) return false;
            for (int i = 0; i < marker.loreJson().size(); i++) {
                if (!Objects.equals(marker.loreJson().get(i), item.loreJson().get(i))) return false;
            }
        } else if (marker.lore() != null && !marker.lore().isEmpty()) {
            if (item.lore() == null || item.lore().size() < marker.lore().size()) return false;
            for (int i = 0; i < marker.lore().size(); i++) {
                if (!Objects.equals(marker.lore().get(i), item.lore().get(i))) return false;
            }
        }

        // Count
        if (marker.count() > 0 && marker.count() != item.count()) return false;

        // Custom model data
        if (marker.customModelData() != 0 && marker.customModelData() != item.customModelData()) return false;

        // Enchant glint
        if (marker.hasEnchantGlint() && !item.hasEnchantGlint()) return false;

        return true;
    }
}
