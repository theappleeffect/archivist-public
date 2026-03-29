package com.archivist.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.StringJoiner;

/**
 * Exports server session data in JSON, CSV, and clipboard-friendly text formats.
 */
public final class LogExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private LogExporter() {}

    /**
     * Exports session data and events as a JSON string.
     */
    public static String exportJson(ServerLogData data, List<LogEvent> events) {
        JsonObject root = new JsonObject();
        root.addProperty("exportedAt", Instant.now().toString());

        JsonObject server = new JsonObject();
        server.addProperty("ip", data.serverInfo().ip());
        server.addProperty("port", data.serverInfo().port());
        server.addProperty("domain", data.serverInfo().domain());
        server.addProperty("brand", data.serverInfo().brand());
        server.addProperty("version", data.serverInfo().version());
        server.addProperty("playerCount", data.serverInfo().playerCount());
        root.add("server", server);

        JsonArray pluginArray = new JsonArray();
        for (ServerLogData.PluginEntry p : data.plugins()) {
            pluginArray.add(p.name());
        }
        root.add("plugins", pluginArray);

        JsonArray eventArray = new JsonArray();
        for (LogEvent event : events) {
            JsonObject obj = new JsonObject();
            obj.addProperty("time", event.getTimestamp());
            obj.addProperty("type", event.getType().name());
            obj.addProperty("data", event.getMessage());
            eventArray.add(obj);
        }
        root.add("events", eventArray);

        return GSON.toJson(root);
    }

    /**
     * Exports session data and events as a CSV string.
     */
    public static String exportCsv(ServerLogData data, List<LogEvent> events) {
        StringBuilder sb = new StringBuilder();

        sb.append("Section,Key,Value\n");
        sb.append(csvRow("Server", "IP", data.serverInfo().ip()));
        sb.append(csvRow("Server", "Port", String.valueOf(data.serverInfo().port())));
        sb.append(csvRow("Server", "Domain", data.serverInfo().domain()));
        sb.append(csvRow("Server", "Brand", data.serverInfo().brand()));
        sb.append(csvRow("Server", "Version", data.serverInfo().version()));
        sb.append(csvRow("Server", "PlayerCount", String.valueOf(data.serverInfo().playerCount())));

        for (ServerLogData.PluginEntry p : data.plugins()) {
            sb.append(csvRow("Plugin", "Name", p.name()));
        }

        sb.append("\nTime,Type,Message\n");
        for (LogEvent event : events) {
            sb.append(csvRow(event.getTimestamp(), event.getType().name(), event.getMessage()));
        }

        return sb.toString();
    }

    /**
     * Exports a plain text summary suitable for clipboard.
     */
    public static String exportClipboard(ServerLogData data, List<LogEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Archivist Server Report ===\n\n");

        sb.append("Server: ").append(data.serverInfo().domain()).append(" (")
                .append(data.serverInfo().ip()).append(":").append(data.serverInfo().port()).append(")\n");
        sb.append("Brand: ").append(data.serverInfo().brand()).append("\n");
        sb.append("Version: ").append(data.serverInfo().version()).append("\n");
        sb.append("Players: ").append(data.serverInfo().playerCount()).append("\n\n");

        if (!data.plugins().isEmpty()) {
            sb.append("Plugins (").append(data.plugins().size()).append("):\n");
            for (ServerLogData.PluginEntry p : data.plugins()) {
                sb.append("  - ").append(p.name()).append("\n");
            }
            sb.append("\n");
        }

        if (!data.detectedAddresses().isEmpty()) {
            sb.append("Detected Addresses:\n");
            for (String addr : data.detectedAddresses()) {
                sb.append("  - ").append(addr).append("\n");
            }
            sb.append("\n");
        }

        if (!events.isEmpty()) {
            sb.append("Events (").append(events.size()).append("):\n");
            for (LogEvent event : events) {
                sb.append("  [").append(event.getTimestamp()).append("] ")
                        .append(event.getType()).append(": ").append(event.getMessage()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Saves content to a file in the exports directory with a timestamped name.
     *
     * @return the path to the saved file
     */
    public static Path saveToFile(String content, String extension) throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir()
                .resolve("archivist").resolve("exports");
        Files.createDirectories(dir);

        String filename = "archivist_" + LocalDateTime.now().format(FILE_TS) + "." + extension;
        Path file = dir.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String csvRow(String a, String b, String c) {
        return escapeCsv(a) + "," + escapeCsv(b) + "," + escapeCsv(c) + "\n";
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
