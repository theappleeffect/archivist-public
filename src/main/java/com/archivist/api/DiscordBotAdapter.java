package com.archivist.api;

import com.archivist.data.ServerLogData;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends server log data to a Discord channel via webhook or bot token.
 *
 * <p>{@code connectionString} = webhook URL or channel endpoint<br>
 * {@code authToken} = bot token (optional for webhooks)</p>
 */
public class DiscordBotAdapter implements DatabaseAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final int EMBED_COLOR = 0xFF9F43; // Amber accent

    private String webhookUrl;
    private Map<String, String> headers;
    private ApiClient client;

    @Override
    public void connect(String connectionString, String authToken) throws Exception {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("Discord webhook URL cannot be empty");
        }
        URI uri = URI.create(connectionString);
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Discord webhook URL missing scheme (http:// or https://): " + connectionString);
        }

        this.webhookUrl = connectionString;
        this.headers = new LinkedHashMap<>();
        if (authToken != null && !authToken.isBlank()) {
            this.headers.put("Authorization", "Bot " + authToken);
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

        String json = buildDiscordPayload(entry);
        ApiResponse response = client.post(webhookUrl, json, headers);

        if (!response.success()) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        LOGGER.info("[Archivist] Discord upload OK: {} -> HTTP {}",
                entry.serverInfo().ip(), response.statusCode());
    }

    @Override
    public boolean testConnection() {
        if (client == null || webhookUrl == null) return false;
        try {
            ApiResponse response = client.get(webhookUrl, headers);
            return response.statusCode() > 0 && response.statusCode() < 500;
        } catch (Exception e) {
            LOGGER.warn("[Archivist] Discord test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String displayName() {
        return "Discord Bot";
    }

    private String buildDiscordPayload(ServerLogData entry) {
        ServerLogData.ServerInfo si = entry.serverInfo();

        JsonObject embed = new JsonObject();
        String displayName = si.domain() != null && !"unknown".equals(si.domain())
                ? si.domain() : si.ip() + ":" + si.port();
        embed.addProperty("title", "Archivist: " + displayName);
        embed.addProperty("color", EMBED_COLOR);

        List<String> descLines = new ArrayList<>();
        descLines.add("**IP:** " + si.ip() + ":" + si.port());
        if (si.domain() != null && !"unknown".equals(si.domain())) {
            descLines.add("**Domain:** " + si.domain());
        }
        descLines.add("**Version:** " + si.version());
        descLines.add("**Brand:** " + si.brand());
        if (si.playerCount() >= 0) {
            descLines.add("**Players:** " + si.playerCount());
        }
        embed.addProperty("description", String.join("\n", descLines));

        // Plugin field
        if (!entry.plugins().isEmpty()) {
            JsonObject pluginField = new JsonObject();
            pluginField.addProperty("name", "Plugins (" + entry.plugins().size() + ")");
            StringBuilder pluginStr = new StringBuilder();
            for (ServerLogData.PluginEntry p : entry.plugins()) {
                if (!pluginStr.isEmpty()) pluginStr.append(", ");
                pluginStr.append(p.name());
            }
            String plugins = pluginStr.toString();
            if (plugins.length() > 1024) {
                plugins = plugins.substring(0, 1020) + "...";
            }
            pluginField.addProperty("value", plugins);
            pluginField.addProperty("inline", false);

            JsonArray fields = new JsonArray();
            fields.add(pluginField);
            embed.add("fields", fields);
        }

        // Footer
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Archivist " + entry.timestamp());
        embed.add("footer", footer);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);

        return new GsonBuilder().create().toJson(payload);
    }
}
