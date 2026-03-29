package com.archivist.detection;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Walks the Brigadier command tree to extract plugin namespaces and commands.
 * Does not directly access network packets.
 */
public final class CommandTreeScanner {

    private static final Set<String> IGNORED_NAMESPACES = DetectionConstants.IGNORED_NAMESPACES;

    private static final Set<String> VERSION_ALIAS_COMMANDS = Set.of(
            "version", "ver", "about", "bukkit:version", "bukkit:ver", "bukkit:about"
    );

    private final PluginGlossary glossary;
    private volatile boolean commandTreeProcessed;

    public CommandTreeScanner(PluginGlossary glossary) {
        this.glossary = glossary;
    }

    /**
     * Processes the command tree from the server dispatcher. One-shot per connection.
     *
     * @param dispatcher the Brigadier command dispatcher
     * @return result containing detected plugins and version alias information
     */
    public CommandTreeResult processCommandTree(CommandDispatcher<?> dispatcher) {
        if (commandTreeProcessed) {
            return new CommandTreeResult(Set.of(), false, null);
        }
        commandTreeProcessed = true;

        Set<String> plugins = new LinkedHashSet<>();
        boolean versionAliasFound = false;
        String versionAliasCommand = null;

        for (CommandNode<?> node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (name == null || name.isEmpty()) continue;

            String lowerName = name.toLowerCase(Locale.ROOT);

            if (name.contains(":")) {
                String namespace = name.substring(0, name.indexOf(':')).toLowerCase(Locale.ROOT);
                if (!IGNORED_NAMESPACES.contains(namespace)) {
                    String resolved = glossary.resolve(namespace).orElse(namespace);
                    plugins.add(resolved);
                }
            } else {
                glossary.resolve(lowerName).ifPresent(plugins::add);
            }

            if (!versionAliasFound && VERSION_ALIAS_COMMANDS.contains(lowerName)) {
                versionAliasFound = true;
                versionAliasCommand = lowerName;
            }
        }

        return new CommandTreeResult(Set.copyOf(plugins), versionAliasFound, versionAliasCommand);
    }

    /**
     * Resets the one-shot guard, allowing the tree to be processed again.
     */
    public void reset() {
        commandTreeProcessed = false;
    }

    /**
     * Result of processing a command tree.
     *
     * @param plugins            the set of detected plugin names
     * @param versionAliasFound  whether a version alias command was found
     * @param versionAliasCommand the alias command that was found, or null
     */
    public record CommandTreeResult(
            Set<String> plugins,
            boolean versionAliasFound,
            String versionAliasCommand
    ) {}
}
