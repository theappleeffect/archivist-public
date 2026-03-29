package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.data.ServerSession;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shows current server summary information.
 */
public final class InfoCommand implements Command {

    private final Supplier<ServerSession> sessionSupplier;

    public InfoCommand(Supplier<ServerSession> sessionSupplier) {
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String description() {
        return "Shows current server summary";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        ServerSession session = sessionSupplier.get();
        if (session == null) {
            output.accept("Not connected to any server.");
            return;
        }

        output.accept("=== Server Info ===");
        output.accept("  IP:       " + session.getIp() + ":" + session.getPort());
        output.accept("  Domain:   " + session.getDomain());
        output.accept("  Version:  " + session.getVersion());
        output.accept("  Brand:    " + session.getBrand());
        output.accept("  Players:  " + session.getPlayerCount());
        output.accept("  Plugins:  " + session.getPlugins().size() + " detected");
    }
}
