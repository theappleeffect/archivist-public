package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.data.EventBus;
import com.archivist.data.LogEvent;
import com.archivist.data.LogExporter;
import com.archivist.data.ServerLogData;
import com.archivist.data.ServerSession;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Exports current server log in json, csv, or clipboard format.
 */
public final class ExportCommand implements Command {

    private static final List<String> FORMATS = List.of("json", "csv", "clipboard");

    private final Supplier<ServerSession> sessionSupplier;
    private final Supplier<EventBus> eventBusSupplier;

    public ExportCommand(Supplier<ServerSession> sessionSupplier,
                         Supplier<EventBus> eventBusSupplier) {
        this.sessionSupplier = sessionSupplier;
        this.eventBusSupplier = eventBusSupplier;
    }

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "Exports current server log (json|csv|clipboard)";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        if (args.length == 0) {
            output.accept("Usage: export <json|csv|clipboard>");
            return;
        }

        String format = args[0].toLowerCase();
        if (!FORMATS.contains(format)) {
            output.accept("Unknown format: " + format + ". Use json, csv, or clipboard.");
            return;
        }

        ServerSession session = sessionSupplier.get();
        if (session == null) {
            output.accept("Not connected to any server.");
            return;
        }

        EventBus eventBus = eventBusSupplier.get();
        ServerLogData data = session.toLogData();
        List<LogEvent> events = eventBus != null ? eventBus.getEvents() : List.of();

        try {
            switch (format) {
                case "json" -> {
                    String json = LogExporter.exportJson(data, events);
                    Path saved = LogExporter.saveToFile(json, "json");
                    output.accept("Exported JSON to: " + saved.toAbsolutePath());
                }
                case "csv" -> {
                    String csv = LogExporter.exportCsv(data, events);
                    Path saved = LogExporter.saveToFile(csv, "csv");
                    output.accept("Exported CSV to: " + saved.toAbsolutePath());
                }
                case "clipboard" -> {
                    String text = LogExporter.exportClipboard(data, events);
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    output.accept("Copied to clipboard.");
                }
            }
        } catch (Exception e) {
            output.accept("Export failed: " + e.getMessage());
        }
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            return FORMATS.stream()
                    .filter(f -> f.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
