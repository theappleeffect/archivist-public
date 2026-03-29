package com.archivist.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves {@link ArchivistConfig} from disk as JSON.
 */
public final class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigManager() {}

    /**
     * Returns the path to the config file.
     */
    public static Path getConfigPath() {
        return FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("settings.json");
    }

    /**
     * Loads the config from disk. Creates a default config file if none exists.
     */
    public static synchronized ArchivistConfig load() {
        Path path = getConfigPath();

        // Migrate from old name (config.json → settings.json)
        if (!Files.exists(path)) {
            Path prevPath = path.resolveSibling("config.json");
            if (Files.exists(prevPath)) {
                try {
                    Files.move(prevPath, path);
                    LOGGER.info("Migrated config from {} to {}", prevPath, path);
                } catch (IOException e) {
                    LOGGER.warn("Failed to migrate config.json to settings.json, will use defaults", e);
                }
            }
        }

        // Migrate from legacy location (config/archivist.json → archivist/settings.json)
        if (!Files.exists(path)) {
            Path oldPath = FabricLoader.getInstance().getConfigDir().resolve("archivist.json");
            if (Files.exists(oldPath)) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.move(oldPath, path);
                    LOGGER.info("Migrated config from {} to {}", oldPath, path);
                } catch (IOException e) {
                    LOGGER.warn("Failed to migrate old config, will use defaults", e);
                }
            }
        }

        if (!Files.exists(path)) {
            ArchivistConfig defaults = new ArchivistConfig();
            save(defaults);
            return defaults;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ArchivistConfig config = GSON.fromJson(json, ArchivistConfig.class);
            if (config == null) {
                return new ArchivistConfig();
            }
            // Guard against partial deserialization leaving collection fields null
            if (config.apiEndpoints == null) config.apiEndpoints = new java.util.ArrayList<>();
            for (ArchivistConfig.ApiEndpointConfig ep : config.apiEndpoints) {
                if (ep.authHeadersEncoded == null) ep.authHeadersEncoded = new java.util.HashMap<>();
            }
            return config;
        } catch (IOException e) {
            LOGGER.error("Failed to load config, using defaults", e);
            return new ArchivistConfig();
        }
    }

    /**
     * Saves the config to disk as formatted JSON.
     */
    public static synchronized void save(ArchivistConfig config) {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }
}
