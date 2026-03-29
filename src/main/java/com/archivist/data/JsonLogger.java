package com.archivist.data;

import com.archivist.util.ArchivistExecutor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persists {@link ServerLogData} to JSON files in the archivist logs directory.
 */
public final class JsonLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;

    private JsonLogger() {}

    /**
     * Returns the logs directory path ({@code .minecraft/archivist/logs/}).
     */
    public static Path getLogsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("logs");
    }

    /**
     * Computes the log filename for the given data.
     */
    public static String getLogFile(ServerLogData data) {
        String domain = data.serverInfo().domain();
        String base;
        if (domain != null && !domain.isEmpty() && !"unknown".equals(domain)) {
            base = domain;
        } else {
            base = data.serverInfo().ip() + "_" + data.serverInfo().port();
        }
        // Sanitize: strip path separators and traversal sequences
        base = base.replace('/', '_').replace('\\', '_').replace("..", "_");
        return base + ".json";
    }

    /**
     * Asynchronously writes the server log data to disk with world-merge and atomic rename.
     */
    public static void writeLog(ServerLogData data) {
        if (data == null) return;
        String filename = getLogFile(data);
        JsonObject json = buildJson(data);

        ArchivistExecutor.execute(() -> {
            Path tmp = null;
            try {
                Path dir = getLogsDirectory();
                Files.createDirectories(dir);
                Path target = dir.resolve(filename);
                tmp = dir.resolve(filename + ".tmp");

                if (Files.exists(target)) {
                    mergeWorlds(json, target);
                }

                Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                tmp = null; // move succeeded, nothing to clean up
            } catch (IOException e) {
                LOGGER.error("Failed to write log file: {}", filename, e);
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            }
        });
    }

    /**
     * Reads and deserializes a log file by filename.
     */
    public static Optional<ServerLogData> readLog(String filename) {
        Path dir = getLogsDirectory();
        Path file = dir.resolve(filename);
        // Prevent path traversal
        if (!file.normalize().startsWith(dir.normalize())) {
            return Optional.empty();
        }
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(parseJson(content));
        } catch (IOException e) {
            LOGGER.error("Failed to read log file: {}", filename, e);
            return Optional.empty();
        }
    }

    /**
     * Reads all JSON log files from the logs directory.
     */
    public static List<ServerLogData> readAllLogs() {
        List<ServerLogData> results = new ArrayList<>();
        Path dir = getLogsDirectory();
        if (!Files.isDirectory(dir)) {
            return results;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    ServerLogData data = parseJson(content);
                    if (data != null) {
                        results.add(data);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to read log file: {}", path.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list log files", e);
        }
        return results;
    }

    private static JsonObject buildJson(ServerLogData data) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("timestamp", data.timestamp());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("ip", data.serverInfo().ip());
        serverInfo.addProperty("port", data.serverInfo().port());
        serverInfo.addProperty("domain", data.serverInfo().domain());
        serverInfo.addProperty("brand", data.serverInfo().brand());
        serverInfo.addProperty("version", data.serverInfo().version());
        serverInfo.addProperty("player_count", data.serverInfo().playerCount());
        serverInfo.addProperty("max_players", data.serverInfo().maxPlayers());
        root.add("server_info", serverInfo);

        JsonArray plugins = new JsonArray();
        for (ServerLogData.PluginEntry p : data.plugins()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", p.name());
            plugins.add(obj);
        }
        root.add("plugins", plugins);

        JsonArray guiPlugins = new JsonArray();
        for (ServerLogData.GuiPluginEntry gp : data.guiPlugins()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("pluginId", gp.pluginId());
            obj.addProperty("pluginName", gp.pluginName());
            obj.addProperty("confidence", gp.confidence());
            obj.addProperty("inventoryTitle", gp.inventoryTitle());
            obj.addProperty("matchedPatterns", gp.matchedPatterns());
            guiPlugins.add(obj);
        }
        root.add("gui_plugins", guiPlugins);

        JsonArray addresses = new JsonArray();
        for (String addr : data.detectedAddresses()) {
            addresses.add(addr);
        }
        root.add("detected_addresses", addresses);

        JsonArray gameAddresses = new JsonArray();
        for (String addr : data.detectedGameAddresses()) {
            gameAddresses.add(addr);
        }
        root.add("detected_game_addresses", gameAddresses);

        JsonArray worlds = new JsonArray();
        for (ServerLogData.WorldEntry w : data.worlds()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", w.timestamp());
            obj.addProperty("dimension", w.dimension());
            if (w.resourcePack() != null) {
                obj.addProperty("resource_pack", w.resourcePack());
            } else {
                obj.add("resource_pack", null);
            }
            worlds.add(obj);
        }
        root.add("worlds", worlds);

        return root;
    }

    private static void mergeWorlds(JsonObject newJson, Path existingFile) throws IOException {
        String existing = Files.readString(existingFile, StandardCharsets.UTF_8);
        JsonObject oldRoot = JsonParser.parseString(existing).getAsJsonObject();

        if (!oldRoot.has("worlds") || !newJson.has("worlds")) return;

        JsonArray oldWorlds = oldRoot.getAsJsonArray("worlds");
        JsonArray newWorlds = newJson.getAsJsonArray("worlds");

        Set<String> existingKeys = new HashSet<>();
        for (JsonElement w : newWorlds) {
            JsonObject wo = w.getAsJsonObject();
            existingKeys.add(worldKey(wo));
        }

        for (JsonElement w : oldWorlds) {
            JsonObject wo = w.getAsJsonObject();
            String key = worldKey(wo);
            if (!existingKeys.contains(key)) {
                newWorlds.add(wo);
                existingKeys.add(key);
            }
        }
    }

    private static String worldKey(JsonObject world) {
        String dim = world.has("dimension") ? world.get("dimension").getAsString() : "";
        String ts = world.has("timestamp") ? world.get("timestamp").getAsString() : "";
        return dim + "|" + ts;
    }

    private static ServerLogData parseJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String timestamp = root.has("timestamp") ? root.get("timestamp").getAsString() : "";

            JsonObject si = root.getAsJsonObject("server_info");
            ServerLogData.ServerInfo serverInfo = new ServerLogData.ServerInfo(
                    getStr(si, "ip", "unknown"),
                    si.has("port") ? si.get("port").getAsInt() : 25565,
                    getStr(si, "domain", "unknown"),
                    getStr(si, "brand", "unknown"),
                    getStr(si, "version", "unknown"),
                    si.has("player_count") ? si.get("player_count").getAsInt() : 0,
                    si.has("max_players") ? si.get("max_players").getAsInt() : 0
            );

            List<ServerLogData.PluginEntry> plugins = new ArrayList<>();
            if (root.has("plugins")) {
                for (JsonElement e : root.getAsJsonArray("plugins")) {
                    try {
                        JsonObject obj = e.getAsJsonObject();
                        plugins.add(new ServerLogData.PluginEntry(getStr(obj, "name", "")));
                    } catch (Exception ex) {
                        LOGGER.warn("Skipping malformed plugin entry", ex);
                    }
                }
            }

            List<ServerLogData.GuiPluginEntry> guiPlugins = new ArrayList<>();
            if (root.has("gui_plugins")) {
                for (JsonElement e : root.getAsJsonArray("gui_plugins")) {
                    try {
                        JsonObject obj = e.getAsJsonObject();
                        guiPlugins.add(new ServerLogData.GuiPluginEntry(
                                getStr(obj, "pluginId", ""),
                                getStr(obj, "pluginName", ""),
                                obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.0,
                                getStr(obj, "inventoryTitle", ""),
                                obj.has("matchedPatterns") ? obj.get("matchedPatterns").getAsInt() : 0
                        ));
                    } catch (Exception ex) {
                        LOGGER.warn("Skipping malformed gui_plugin entry", ex);
                    }
                }
            }

            List<String> detectedAddresses = new ArrayList<>();
            if (root.has("detected_addresses")) {
                for (JsonElement e : root.getAsJsonArray("detected_addresses")) {
                    try {
                        detectedAddresses.add(e.getAsString());
                    } catch (Exception ex) {
                        LOGGER.warn("Skipping malformed detected_address entry", ex);
                    }
                }
            }

            List<String> detectedGameAddresses = new ArrayList<>();
            if (root.has("detected_game_addresses")) {
                for (JsonElement e : root.getAsJsonArray("detected_game_addresses")) {
                    try {
                        detectedGameAddresses.add(e.getAsString());
                    } catch (Exception ex) {
                        LOGGER.warn("Skipping malformed detected_game_address entry", ex);
                    }
                }
            }

            List<ServerLogData.WorldEntry> worlds = new ArrayList<>();
            if (root.has("worlds")) {
                for (JsonElement e : root.getAsJsonArray("worlds")) {
                    try {
                        JsonObject obj = e.getAsJsonObject();
                        String rp = obj.has("resource_pack") && !obj.get("resource_pack").isJsonNull()
                                ? obj.get("resource_pack").getAsString()
                                : null;
                        worlds.add(new ServerLogData.WorldEntry(
                                getStr(obj, "timestamp", ""),
                                getStr(obj, "dimension", ""),
                                rp
                        ));
                    } catch (Exception ex) {
                        LOGGER.warn("Skipping malformed world entry", ex);
                    }
                }
            }

            return new ServerLogData(timestamp, serverInfo, plugins, guiPlugins,
                    detectedAddresses, detectedGameAddresses, worlds);
        } catch (Exception e) {
            LOGGER.error("Failed to parse log JSON", e);
            return null;
        }
    }

    private static String getStr(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }
}
