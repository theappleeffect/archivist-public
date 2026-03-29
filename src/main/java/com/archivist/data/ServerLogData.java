package com.archivist.data;

import java.util.List;

/**
 * Immutable snapshot of a server session for persistence.
 */
public record ServerLogData(
        String timestamp,
        ServerInfo serverInfo,
        List<PluginEntry> plugins,
        List<GuiPluginEntry> guiPlugins,
        List<String> detectedAddresses,
        List<String> detectedGameAddresses,
        List<WorldEntry> worlds
) {

    public record ServerInfo(
            String ip,
            int port,
            String domain,
            String brand,
            String version,
            int playerCount,
            int maxPlayers
    ) {}

    public record PluginEntry(String name) {}

    public record GuiPluginEntry(
            String pluginId,
            String pluginName,
            double confidence,
            String inventoryTitle,
            int matchedPatterns
    ) {}

    public record WorldEntry(
            String timestamp,
            String dimension,
            String resourcePack
    ) {}
}
