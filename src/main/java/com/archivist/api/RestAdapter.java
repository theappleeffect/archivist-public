package com.archivist.api;

import com.archivist.data.ServerLogData;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API adapter that uses {@link ApiClient} to push server log data
 * as JSON to a user-configured endpoint.
 *
 * <p>Push format matches the local JSON log schema wrapped in a {@code servers} array.</p>
 */
public class RestAdapter implements DatabaseAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    private String url;
    private Map<String, String> headers;
    private ApiClient client;

    @Override
    public void connect(String connectionString, String authToken) throws Exception {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("REST URL cannot be empty");
        }
        // Validate URL format and scheme
        URI uri = URI.create(connectionString);
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("REST URL missing scheme (http:// or https://): " + connectionString);
        }

        this.url = connectionString;
        this.headers = new LinkedHashMap<>();
        if (authToken != null && !authToken.isBlank()) {
            this.headers.put("X-API-Key", authToken);
        }
        this.client = new ApiClient();
    }

    @Override
    public void disconnect() {
        client = null;
    }

    @Override
    public void upload(ServerLogData entry) throws Exception {
        if (client == null) throw new IllegalStateException("Not connected");

        String json = buildPushPayload(entry);
        ApiResponse response = client.post(url, json, headers);

        if (!response.success()) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        LOGGER.info("[Archivist] REST upload OK: {} -> HTTP {}",
                entry.serverInfo().ip(), response.statusCode());
    }

    @Override
    public boolean testConnection() {
        if (client == null || url == null) return false;
        try {
            ApiResponse response = client.get(url, headers);
            return response.statusCode() > 0 && response.statusCode() < 500;
        } catch (Exception e) {
            LOGGER.warn("[Archivist] REST test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String displayName() {
        return "REST API";
    }

    /**
     * Builds the push payload wrapping a single entry in the {@code servers} array.
     */
    static String buildPushPayload(ServerLogData data) {
        JsonObject root = new JsonObject();
        JsonArray servers = new JsonArray();
        servers.add(buildServerJson(data));
        root.add("servers", servers);
        return new GsonBuilder().create().toJson(root);
    }

    /**
     * Serializes a single {@link ServerLogData} into the standard JSON format.
     */
    static JsonObject buildServerJson(ServerLogData data) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", data.timestamp());

        // server_info
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("ip", data.serverInfo().ip());
        serverInfo.addProperty("port", data.serverInfo().port());
        serverInfo.addProperty("domain", data.serverInfo().domain());
        serverInfo.addProperty("brand", data.serverInfo().brand());
        serverInfo.addProperty("version", data.serverInfo().version());
        serverInfo.addProperty("player_count", data.serverInfo().playerCount());
        serverInfo.addProperty("max_players", data.serverInfo().maxPlayers());
        entry.add("server_info", serverInfo);

        // plugins
        JsonArray plugins = new JsonArray();
        for (ServerLogData.PluginEntry p : data.plugins()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", p.name());
            plugins.add(obj);
        }
        entry.add("plugins", plugins);

        // gui_plugins
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
        entry.add("gui_plugins", guiPlugins);

        // detected_addresses
        JsonArray addresses = new JsonArray();
        data.detectedAddresses().forEach(addresses::add);
        entry.add("detected_addresses", addresses);

        // detected_game_addresses
        JsonArray gameAddresses = new JsonArray();
        data.detectedGameAddresses().forEach(gameAddresses::add);
        entry.add("detected_game_addresses", gameAddresses);

        // worlds
        JsonArray worlds = new JsonArray();
        for (ServerLogData.WorldEntry w : data.worlds()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("timestamp", w.timestamp());
            obj.addProperty("dimension", w.dimension());
            if (w.resourcePack() != null) {
                obj.addProperty("resource_pack", w.resourcePack());
            }
            worlds.add(obj);
        }
        entry.add("worlds", worlds);

        return entry;
    }
}
