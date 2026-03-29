package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.command.CommandRegistry;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lists all available commands with descriptions.
 */
public final class HelpCommand implements Command {

    private final Supplier<CommandRegistry> registrySupplier;

    public HelpCommand(Supplier<CommandRegistry> registrySupplier) {
        this.registrySupplier = registrySupplier;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Lists all available commands";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        output.accept("=== Archivist Console Commands ===");
        CommandRegistry registry = registrySupplier.get();
        for (Command cmd : registry.getAllCommands()) {
            output.accept("  " + cmd.name() + " - " + cmd.description());
        }
    }
}
