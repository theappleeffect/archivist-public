package com.archivist.data;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares two {@link ServerLogData} snapshots and produces a {@link ServerDiff}.
 */
public final class DiffEngine {

    private DiffEngine() {}

    /**
     * Result of comparing two server log snapshots.
     */
    public record ServerDiff(
            boolean versionChanged,
            String oldVersion,
            String newVersion,
            boolean brandChanged,
            String oldBrand,
            String newBrand,
            boolean playerCountChanged,
            int oldPlayerCount,
            int newPlayerCount,
            Set<String> pluginsAdded,
            Set<String> pluginsRemoved,
            Set<String> pluginsCommon,
            boolean worldsChanged,
            Set<String> newAddresses
    ) {}

    /**
     * Computes the differences between an older and newer snapshot of the same server.
     */
    public static ServerDiff diff(ServerLogData older, ServerLogData newer) {
        Objects.requireNonNull(older, "older must not be null");
        Objects.requireNonNull(newer, "newer must not be null");

        String oldVersion = older.serverInfo().version();
        String newVersion = newer.serverInfo().version();
        boolean versionChanged = !Objects.equals(oldVersion, newVersion);

        String oldBrand = older.serverInfo().brand();
        String newBrand = newer.serverInfo().brand();
        boolean brandChanged = !Objects.equals(oldBrand, newBrand);

        int oldPlayerCount = older.serverInfo().playerCount();
        int newPlayerCount = newer.serverInfo().playerCount();
        boolean playerCountChanged = oldPlayerCount != newPlayerCount;

        Set<String> oldPlugins = older.plugins().stream()
                .map(ServerLogData.PluginEntry::name)
                .collect(Collectors.toSet());
        Set<String> newPlugins = newer.plugins().stream()
                .map(ServerLogData.PluginEntry::name)
                .collect(Collectors.toSet());

        Set<String> pluginsAdded = new HashSet<>(newPlugins);
        pluginsAdded.removeAll(oldPlugins);

        Set<String> pluginsRemoved = new HashSet<>(oldPlugins);
        pluginsRemoved.removeAll(newPlugins);

        Set<String> pluginsCommon = new HashSet<>(oldPlugins);
        pluginsCommon.retainAll(newPlugins);

        boolean worldsChanged = !Objects.equals(older.worlds(), newer.worlds());

        Set<String> oldAddresses = new HashSet<>(older.detectedAddresses());
        Set<String> newAddresses = new HashSet<>(newer.detectedAddresses());
        newAddresses.removeAll(oldAddresses);

        return new ServerDiff(
                versionChanged, oldVersion, newVersion,
                brandChanged, oldBrand, newBrand,
                playerCountChanged, oldPlayerCount, newPlayerCount,
                pluginsAdded, pluginsRemoved, pluginsCommon,
                worldsChanged, newAddresses
        );
    }
}
