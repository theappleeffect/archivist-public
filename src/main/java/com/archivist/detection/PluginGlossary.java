package com.archivist.detection;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps command/namespace aliases to canonical plugin names.
 * Loads a bundled glossary from classpath and allows user overrides from the config directory.
 * Thread-safe: uses ConcurrentHashMap and atomic swap on load.
 */
public final class PluginGlossary {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final String BUNDLED_PATH = "/assets/archivist/glossary.json";
    private static final String USER_OVERRIDE_FILE = "archivist/glossary.json";

    private volatile Map<String, String> entries = new ConcurrentHashMap<>();

    /**
     * Loads the bundled glossary from classpath resources, then merges user overrides
     * from the config directory. User entries win on conflict.
     */
    public void load() {
        Map<String, String> newEntries = new ConcurrentHashMap<>();
        Map<String, String> old = entries;
        entries = newEntries;
        loadBundled();
        loadUserOverrides();
        LOGGER.info("PluginGlossary loaded {} entries", entries.size());
    }

    /**
     * Resolves a key to its canonical plugin name.
     *
     * @param key the alias or namespace to look up (case-insensitive)
     * @return the canonical plugin name, or empty if not found
     */
    public Optional<String> resolve(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(entries.get(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Checks whether the glossary contains a mapping for the given key.
     *
     * @param key the alias or namespace to check (case-insensitive)
     */
    public boolean contains(String key) {
        if (key == null) return false;
        return entries.containsKey(key.toLowerCase(Locale.ROOT));
    }

    private void loadBundled() {
        try (InputStream is = PluginGlossary.class.getResourceAsStream(BUNDLED_PATH)) {
            if (is == null) {
                LOGGER.warn("Bundled glossary not found at {}", BUNDLED_PATH);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                parseAndMerge(reader);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load bundled glossary", e);
        }
    }

    private void loadUserOverrides() {
        Path configDir = FabricLoader.getInstance().getGameDir();
        Path userFile = configDir.resolve(USER_OVERRIDE_FILE);

        // Migrate from old location
        if (!Files.exists(userFile)) {
            Path oldFile = configDir.resolve("archivist-glossary.json");
            if (Files.exists(oldFile)) {
                try {
                    Files.createDirectories(userFile.getParent());
                    Files.move(oldFile, userFile);
                } catch (Exception ignored) {}
            }
        }

        if (!Files.exists(userFile)) return;

        try (BufferedReader reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
            parseAndMerge(reader);
            LOGGER.info("Loaded user glossary overrides from {}", userFile);
        } catch (IOException e) {
            LOGGER.error("Failed to load user glossary from {}", userFile, e);
        }
    }

    private void parseAndMerge(BufferedReader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonObject entriesObj = root.getAsJsonObject("entries");
        if (entriesObj == null) return;

        for (Map.Entry<String, JsonElement> entry : entriesObj.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().getAsString();
            entries.put(key, value);
        }
    }
}
