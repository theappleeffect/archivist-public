package com.archivist.detection.fingerprint;

import com.archivist.detection.fingerprint.model.GuiRule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads and manages GUI detection rules from the archivist/rules/ directory.
 */
public class GuiRuleDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rulesDir;
    private List<GuiRule> rules = new ArrayList<>();

    public GuiRuleDatabase(Path gameDir) {
        this.rulesDir = gameDir.resolve("archivist/rules");
    }

    public void load() {
        rules.clear();
        try {
            Files.createDirectories(rulesDir);
        } catch (IOException e) {
            LOGGER.warn("[Archivist] Failed to create rules directory: {}", e.getMessage());
            return;
        }

        try (var stream = Files.list(rulesDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    String json = Files.readString(file);
                    GuiRule rule = GSON.fromJson(json, GuiRule.class);
                    if (rule != null && rule.pluginId() != null && rule.patterns() != null) {
                        rules.add(rule);
                        LOGGER.info("[Archivist] Loaded GUI rule: {} ({} patterns)",
                                rule.pluginName(), rule.patterns().size());
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Archivist] Failed to load rule {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Archivist] Failed to scan rules directory: {}", e.getMessage());
        }
    }

    public void reload() {
        load();
    }

    public List<GuiRule> getAll() {
        return Collections.unmodifiableList(rules);
    }

    public void delete(String pluginId) {
        try {
            Path file = rulesDir.resolve(pluginId + ".json");
            Files.deleteIfExists(file);
            rules.removeIf(r -> r.pluginId().equals(pluginId));
            LOGGER.info("[Archivist] Deleted GUI rule: {}", pluginId);
        } catch (IOException e) {
            LOGGER.warn("[Archivist] Failed to delete rule {}: {}", pluginId, e.getMessage());
        }
    }

    public void save(GuiRule rule) {
        try {
            Files.createDirectories(rulesDir);
            Path file = rulesDir.resolve(rule.pluginId() + ".json");
            Files.writeString(file, GSON.toJson(rule));
            LOGGER.info("[Archivist] Saved GUI rule: {}", rule.pluginName());

            // Update in-memory list
            rules.removeIf(r -> r.pluginId().equals(rule.pluginId()));
            rules.add(rule);
        } catch (IOException e) {
            LOGGER.warn("[Archivist] Failed to save rule {}: {}", rule.pluginId(), e.getMessage());
        }
    }
}
