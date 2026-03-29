package com.archivist.command.commands;

import com.archivist.api.ApiSyncManager;
import com.archivist.command.Command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * API sync management. Subcommands: status, sync, download, test, reset, set.
 */
public final class DbCommand implements Command {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "sync", "download", "test", "reset", "set"
    );

    private final Supplier<ApiSyncManager> syncManagerSupplier;

    public DbCommand(Supplier<ApiSyncManager> syncManagerSupplier) {
        this.syncManagerSupplier = syncManagerSupplier;
    }

    @Override
    public String name() {
        return "db";
    }

    @Override
    public String description() {
        return "API sync management (status|sync|download|test|reset|set)";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        if (args.length == 0) {
            output.accept("Usage: db <status|sync|download|test|reset|set>");
            return;
        }

        ApiSyncManager manager = syncManagerSupplier.get();
        if (manager == null) {
            output.accept("API sync manager not available.");
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                output.accept("=== API Sync Status ===");
                output.accept("  Status:  " + manager.getStatus());
                output.accept("  Message: " + manager.getStatusMessage());
            }
            case "sync" -> {
                output.accept("Pushing all local logs to configured endpoints...");
                manager.pushAll(response -> {
                    if (response.success()) {
                        output.accept("Sync complete.");
                    } else {
                        output.accept("Sync failed: " + response.body());
                    }
                });
            }
            case "download" -> {
                output.accept("Downloading logs from configured endpoints...");
                manager.downloadAll(response -> {
                    if (response.success()) {
                        output.accept("Download complete.");
                    } else {
                        output.accept("Download failed: " + response.body());
                    }
                });
            }
            case "test" -> {
                output.accept("Testing connection to configured endpoints...");
                output.accept("  Current status: " + manager.getStatus());
                output.accept("  Use the GUI settings panel to test individual endpoints.");
            }
            case "reset" -> {
                output.accept("Use the GUI settings panel to reset remote logs.");
                output.accept("  Current status: " + manager.getStatus());
            }
            case "set" -> {
                if (args.length < 3) {
                    output.accept("Usage: db set <key> <value>");
                    output.accept("  Configure API endpoints via the GUI settings panel.");
                    return;
                }
                output.accept("API endpoint configuration is managed through the GUI settings panel.");
            }
            default -> output.accept("Unknown subcommand: " + sub + ". Use: status, sync, download, test, reset, set.");
        }
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
