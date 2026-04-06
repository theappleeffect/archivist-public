package com.archivist.detection;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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

    public CommandTreeResult processCommandTree(CommandDispatcher<?> dispatcher) {
        if (commandTreeProcessed) {
            return new CommandTreeResult(Set.of(), Set.of(), false, null, false, 0);
        }
        commandTreeProcessed = true;

        Set<String> uniqueNamespaces = new LinkedHashSet<>();
        for (CommandNode<?> node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.contains(":")) {
                String namespace = name.substring(0, name.indexOf(':')).toLowerCase(Locale.ROOT);
                if (!IGNORED_NAMESPACES.contains(namespace)
                        && !DetectionConstants.CLIENT_MOD_NAMESPACES.contains(namespace)) {
                    uniqueNamespaces.add(namespace);
                }
            }
        }
        boolean hasNamespaces = !uniqueNamespaces.isEmpty();

        Set<String> namespacedPlugins = new LinkedHashSet<>();
        Set<String> glossaryPlugins = new LinkedHashSet<>();
        boolean versionAliasFound = false;
        String versionAliasCommand = null;

        for (CommandNode<?> node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (name == null || name.isEmpty()) continue;

            String lowerName = name.toLowerCase(Locale.ROOT);

            if (name.contains(":")) {
                String namespace = name.substring(0, name.indexOf(':')).toLowerCase(Locale.ROOT);
                if (IGNORED_NAMESPACES.contains(namespace) || DetectionConstants.CLIENT_MOD_NAMESPACES.contains(namespace)) continue;
                String resolved = glossary.resolve(namespace).or(() -> glossary.resolveFuzzy(namespace)).orElse(namespace);
                if (resolved.equals(namespace) && !glossary.contains(namespace)) {
                    glossary.trackUnresolved(namespace);
                }
                namespacedPlugins.add(resolved);
            } else {
                if (uniqueNamespaces.size() <= 5) {
                    glossary.resolve(lowerName).or(() -> glossary.resolveFuzzy(lowerName)).ifPresent(glossaryPlugins::add);
                }
            }

            if (!versionAliasFound && VERSION_ALIAS_COMMANDS.contains(lowerName)) {
                versionAliasFound = true;
                versionAliasCommand = lowerName;
            }
        }

        return new CommandTreeResult(Set.copyOf(namespacedPlugins), Set.copyOf(glossaryPlugins),
                versionAliasFound, versionAliasCommand, hasNamespaces, uniqueNamespaces.size());
    }

    public void reset() {
        commandTreeProcessed = false;
    }

    public record CommandTreeResult(
            Set<String> namespacedPlugins,
            Set<String> glossaryPlugins,
            boolean versionAliasFound,
            String versionAliasCommand,
            boolean hasNamespaces,
            int namespaceCount
    ) {
        public Set<String> plugins() {
            Set<String> all = new LinkedHashSet<>(namespacedPlugins);
            all.addAll(glossaryPlugins);
            return Set.copyOf(all);
        }
    }
}
