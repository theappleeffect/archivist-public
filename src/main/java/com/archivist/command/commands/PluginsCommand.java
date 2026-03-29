package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.data.ServerSession;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lists all detected plugins alphabetically.
 */
public final class PluginsCommand implements Command {

    private final Supplier<ServerSession> sessionSupplier;

    public PluginsCommand(Supplier<ServerSession> sessionSupplier) {
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public String name() {
        return "plugins";
    }

    @Override
    public String description() {
        return "Lists all detected plugins";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        ServerSession session = sessionSupplier.get();
        if (session == null) {
            output.accept("Not connected to any server.");
            return;
        }

        Set<String> plugins = session.getPlugins();
        if (plugins.isEmpty()) {
            output.accept("No plugins detected yet.");
            return;
        }

        List<String> sorted = plugins.stream().sorted().toList();
        output.accept("=== Detected Plugins (" + sorted.size() + ") ===");
        for (String plugin : sorted) {
            output.accept("  - " + plugin);
        }
    }
}
