package com.archivist.api.automation;

import com.archivist.util.ArchivistExecutor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks automation progress across the server list. Persisted to disk
 * so progress survives client restarts.
 */
public final class SequenceState {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum RunStatus { IDLE, RUNNING, PAUSED, COMPLETED }

    public RunStatus status = RunStatus.IDLE;
    public String activeListName = "";
    public int currentIndex = 0;
    public String currentServer = "";
    public Set<String> completedServers = new LinkedHashSet<>();
    /** Maps server IP → failure reason. */
    public Map<String, String> failedServers = new LinkedHashMap<>();
    public long startedAtEpochMs = 0;
    public long stoppedAtEpochMs = 0;
    public long lastActivityEpochMs = 0;

    /** Runtime-only display field — not persisted. */
    public transient String currentPhase = "idle";
    public transient int ticksSincePhaseStart = 0;

    private static Path getStatePath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("archivist").resolve("automation").resolve("automation_state.json");
    }

    public static synchronized SequenceState load() {
        Path path = getStatePath();
        if (!Files.exists(path)) {
            return new SequenceState();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            SequenceState state = GSON.fromJson(json, SequenceState.class);
            if (state == null) return new SequenceState();
            // Don't auto-resume on restart
            if (state.status == RunStatus.RUNNING) {
                state.status = RunStatus.PAUSED;
            }
            // Ensure collections are initialized (Gson may leave them null)
            if (state.completedServers == null) state.completedServers = new LinkedHashSet<>();
            if (state.failedServers == null) state.failedServers = new LinkedHashMap<>();
            return state;
        } catch (IOException e) {
            LOGGER.error("Failed to load automation state, using defaults", e);
            return new SequenceState();
        }
    }

    public void save() {
        ArchivistExecutor.execute(() -> {
            Path path = getStatePath();
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Failed to save automation state", e);
            }
        });
    }

    public boolean isServerCompleted(String ip) {
        return completedServers.contains(ip);
    }

    public void markCompleted(String ip) {
        completedServers.add(ip);
        failedServers.remove(ip);
        lastActivityEpochMs = System.currentTimeMillis();
        save();
    }

    public void markFailed(String ip, String reason) {
        failedServers.put(ip, reason != null ? reason : "unknown");
        lastActivityEpochMs = System.currentTimeMillis();
        save();
    }

    /** Get the failure reason for a server, or null if not failed. */
    public String getFailureReason(String ip) {
        return failedServers.get(ip);
    }

    /**
     * Returns the next unvisited server from the list starting at {@code currentIndex},
     * or {@code null} if all servers have been completed or failed.
     *
     * @param extraSkip optional predicate to skip additional servers (e.g., already logged)
     */
    public String getNextServer(List<String> fullList, Predicate<String> extraSkip) {
        if (fullList == null || fullList.isEmpty()) return null;
        int size = fullList.size();
        for (int i = 0; i < size; i++) {
            int idx = (currentIndex + i) % size;
            String server = fullList.get(idx);
            if (!completedServers.contains(server)
                    && !failedServers.containsKey(server)
                    && (extraSkip == null || !extraSkip.test(server))) {
                currentIndex = idx;
                return server;
            }
        }
        return null;
    }

    /** Overload without extra skip predicate for backwards compatibility. */
    public String getNextServer(List<String> fullList) {
        return getNextServer(fullList, null);
    }

    public int getCompletedCount() {
        return completedServers.size();
    }

    public int getFailedCount() {
        return failedServers.size();
    }

    public int getRemainingCount(int totalServers) {
        return Math.max(0, totalServers - completedServers.size() - failedServers.size());
    }

    public void reset() {
        status = RunStatus.IDLE;
        currentIndex = 0;
        currentServer = "";
        completedServers.clear();
        failedServers.clear();
        startedAtEpochMs = 0;
        stoppedAtEpochMs = 0;
        lastActivityEpochMs = 0;
        currentPhase = "idle";
        ticksSincePhaseStart = 0;
    }
}
