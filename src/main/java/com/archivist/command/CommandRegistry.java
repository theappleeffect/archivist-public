package com.archivist.command;

import java.util.*;
import java.util.function.Consumer;

public final class CommandRegistry {
    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command cmd) {
        commands.put(cmd.name().toLowerCase(Locale.ROOT), cmd);
    }

    public void dispatch(String input, Consumer<String> output) {
        String[] parts = input.trim().split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        Command cmd = commands.get(name);
        if (cmd == null) {
            output.accept("Unknown command: " + name + ". Type 'help' for available commands.");
            return;
        }
        try {
            cmd.execute(args, output);
        } catch (Exception e) {
            output.accept("Error: " + e.getMessage());
        }
    }

    public List<String> getCompletions(String partial) {
        String lower = partial.toLowerCase(Locale.ROOT).trim();
        if (!lower.contains(" ")) {
            // Complete command name
            return commands.keySet().stream()
                    .filter(n -> n.startsWith(lower))
                    .toList();
        }
        // Complete command args
        String[] parts = lower.split("\\s+", 2);
        Command cmd = commands.get(parts[0]);
        if (cmd != null) {
            String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
            return cmd.complete(args);
        }
        return List.of();
    }

    public Collection<Command> getAllCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }
}
