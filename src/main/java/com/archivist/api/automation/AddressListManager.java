package com.archivist.api.automation;

import com.archivist.util.ArchivistExecutor;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages plain-text server list files in {@code .minecraft/archivist/automation/server-list/}.
 * Each file contains one server IP/domain per line; blank lines and {@code #} comments are skipped.
 */
public final class AddressListManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    private AddressListManager() {}

    public static Path getBaseDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("automation").resolve("server-list");
    }

    public static void ensureDirectory() {
        try {
            Files.createDirectories(getBaseDirectory());
        } catch (IOException e) {
            LOGGER.warn("Failed to create server-list directory", e);
        }
    }

    /**
     * Returns a list of available server list filenames (without .txt extension).
     */
    public static List<String> getAvailableLists() {
        Path dir = getBaseDirectory();
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                names.add(filename.substring(0, filename.length() - 4)); // strip .txt
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list server lists", e);
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Loads a server list by name. Returns trimmed, non-blank, non-comment lines.
     */
    public static List<String> loadList(String listName) {
        Path file = getBaseDirectory().resolve(listName + ".txt");
        if (!Files.exists(file)) return Collections.emptyList();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> servers = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (String line : lines) {
                String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Normalize: strip default port :25565
                if (trimmed.endsWith(":25565")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 6);
                }
                if (trimmed.isEmpty()) continue;
                // Deduplicate
                if (seen.add(trimmed)) {
                    servers.add(trimmed);
                }
            }
            return servers;
        } catch (IOException e) {
            LOGGER.warn("Failed to load server list: {}", listName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Saves a server list to disk asynchronously.
     */
    public static void saveList(String listName, List<String> servers) {
        ArchivistExecutor.execute(() -> {
            Path file = getBaseDirectory().resolve(listName + ".txt");
            try {
                ensureDirectory();
                Files.writeString(file, String.join("\n", servers) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Failed to save server list: {}", listName, e);
            }
        });
    }

    /**
     * Creates a new empty server list file.
     */
    public static void createList(String listName) {
        saveList(listName, Collections.emptyList());
    }

    /**
     * Deletes a server list file.
     */
    public static void deleteList(String listName) {
        ArchivistExecutor.execute(() -> {
            Path file = getBaseDirectory().resolve(listName + ".txt");
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warn("Failed to delete server list: {}", listName, e);
            }
        });
    }
}
