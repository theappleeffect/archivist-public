package com.archivist.gui;

import com.archivist.ArchivistMod;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Saves and loads window positions/sizes/visibility to gui-config.json.
 */
public final class WindowStateManager {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getGameDir()
            .resolve("archivist").resolve("gui-config.json");

    private WindowStateManager() {}

    public record WindowState(int x, int y, int width, int height, boolean visible, boolean minimized) {}

    /**
     * Load all saved window states from disk.
     */
    public static Map<String, WindowState> load() {
        Map<String, WindowState> states = new HashMap<>();
        if (!Files.exists(CONFIG_PATH)) return states;
        try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("windowStates")) {
                JsonObject ws = root.getAsJsonObject("windowStates");
                for (var entry : ws.entrySet()) {
                    JsonObject s = entry.getValue().getAsJsonObject();
                    states.put(entry.getKey(), new WindowState(
                            s.get("x").getAsInt(),
                            s.get("y").getAsInt(),
                            s.get("width").getAsInt(),
                            s.get("height").getAsInt(),
                            s.has("visible") && s.get("visible").getAsBoolean(),
                            s.has("minimized") && s.get("minimized").getAsBoolean()
                    ));
                }
            }
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to load gui-config.json", e);
        }
        return states;
    }

    /**
     * Save window states to disk.
     */
    public static void save(Map<String, WindowState> states) {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (!Files.exists(parent)) Files.createDirectories(parent);

            JsonObject root = new JsonObject();
            JsonObject ws = new JsonObject();
            for (var entry : states.entrySet()) {
                JsonObject s = new JsonObject();
                WindowState state = entry.getValue();
                s.addProperty("x", state.x());
                s.addProperty("y", state.y());
                s.addProperty("width", state.width());
                s.addProperty("height", state.height());
                s.addProperty("visible", state.visible());
                s.addProperty("minimized", state.minimized());
                ws.add(entry.getKey(), s);
            }
            root.add("windowStates", ws);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Path tmp = CONFIG_PATH.resolveSibling("gui-config.json.tmp");
            Files.writeString(tmp, gson.toJson(root));
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to save gui-config.json", e);
        }
    }
}
