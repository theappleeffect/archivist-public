package com.archivist.detection.fingerprint;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads heuristic rules from /assets/archivist/heuristics.json and matches
 * against captured items to detect server plugins.
 */
public class PluginHeuristics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Archivist/PluginHeuristics");
    private static final String HEURISTICS_PATH = "/assets/archivist/heuristics.json";

    private final List<HeuristicEntry> entries = new ArrayList<>();

    /**
     * A single heuristic entry describing how to detect a plugin.
     */
    private record HeuristicEntry(
            String plugin,
            List<String> namePatterns,
            List<String> loreKeywords
    ) {}

    /**
     * Loads the bundled heuristics from the resource path.
     */
    public void load() {
        entries.clear();
        try (InputStream in = getClass().getResourceAsStream(HEURISTICS_PATH)) {
            if (in == null) {
                LOGGER.warn("Heuristics file not found at {}", HEURISTICS_PATH);
                return;
            }
            Gson gson = new Gson();
            JsonObject root = gson.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);

            JsonArray arr = root.getAsJsonArray("entries");
            if (arr == null) {
                LOGGER.warn("No 'entries' array in heuristics file");
                return;
            }

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String plugin = obj.get("plugin").getAsString();

                List<String> namePatterns = new ArrayList<>();
                JsonArray names = obj.getAsJsonArray("namePatterns");
                if (names != null) {
                    for (JsonElement n : names) {
                        namePatterns.add(n.getAsString());
                    }
                }

                List<String> loreKeywords = new ArrayList<>();
                JsonArray lores = obj.getAsJsonArray("loreKeywords");
                if (lores != null) {
                    for (JsonElement l : lores) {
                        loreKeywords.add(l.getAsString());
                    }
                }

                entries.add(new HeuristicEntry(plugin, namePatterns, loreKeywords));
            }

            LOGGER.info("Loaded {} heuristic entries", entries.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load heuristics", e);
        }
    }

    /**
     * Matches captured items against loaded heuristic rules.
     *
     * @param inventoryTitle the title of the opened inventory
     * @param items          the items extracted from the GUI
     * @return deduplicated list of detected plugin names
     */
    public List<String> match(String inventoryTitle, List<CapturedItem> items) {
        Set<String> detected = new LinkedHashSet<>();

        for (HeuristicEntry entry : entries) {
            boolean matched = false;

            // Name rules: any item's displayName contains any namePattern (case-insensitive)
            if (!entry.namePatterns().isEmpty()) {
                for (CapturedItem item : items) {
                    if (item.displayName() == null) continue;
                    String lower = item.displayName().toLowerCase();
                    for (String pattern : entry.namePatterns()) {
                        if (lower.contains(pattern.toLowerCase())) {
                            matched = true;
                            break;
                        }
                    }
                    if (matched) break;
                }
            }

            // Lore rules: any item's any lore line contains any loreKeyword (case-insensitive)
            if (!matched && !entry.loreKeywords().isEmpty()) {
                for (CapturedItem item : items) {
                    if (item.lore() == null) continue;
                    for (String loreLine : item.lore()) {
                        String lower = loreLine.toLowerCase();
                        for (String keyword : entry.loreKeywords()) {
                            if (lower.contains(keyword.toLowerCase())) {
                                matched = true;
                                break;
                            }
                        }
                        if (matched) break;
                    }
                    if (matched) break;
                }
            }

            if (matched) {
                detected.add(entry.plugin());
            }
        }

        // Special rule: if any item has customModelData > 1000, add "CustomModelData-Plugin"
        for (CapturedItem item : items) {
            if (item.customModelData() > 1000) {
                detected.add("CustomModelData-Plugin");
                break;
            }
        }

        return new ArrayList<>(detected);
    }
}
