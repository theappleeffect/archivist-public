package com.archivist.data;

import com.archivist.detection.Detection;
import com.archivist.detection.DetectionType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory mutable model for the current server connection.
 * All fields are thread-safe via volatile, concurrent collections, or synchronization.
 */
public final class ServerSession {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private volatile String ip = "unknown";
    private volatile int port = 25565;
    private volatile String domain = "unknown";
    private volatile String brand = "unknown";
    private volatile String version = "unknown";
    private volatile String serverSoftware = "";
    private volatile String dimension = "minecraft:overworld";
    private volatile String resourcePack = null;
    private volatile int playerCount = 0;
    private volatile int maxPlayers = 0;

    private final Set<String> plugins = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<ServerLogData.GuiPluginEntry> guiPlugins = new CopyOnWriteArrayList<>();
    private final Set<String> detectedAddresses = ConcurrentHashMap.newKeySet();
    private final Set<String> detectedGameAddresses = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<ServerLogData.WorldEntry> worlds = new CopyOnWriteArrayList<>();

    // Confidence-based detection storage
    private final CopyOnWriteArrayList<Detection> detections = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Double> pluginConfidence = new ConcurrentHashMap<>();

    private final EventBus events;

    public ServerSession(EventBus events) {
        this.events = events;
    }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getServerSoftware() { return serverSoftware; }
    public void setServerSoftware(String serverSoftware) { this.serverSoftware = serverSoftware; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public String getResourcePack() { return resourcePack; }
    public void setResourcePack(String resourcePack) { this.resourcePack = resourcePack; }
    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public Set<String> getPlugins() { return Collections.unmodifiableSet(plugins); }
    public List<ServerLogData.GuiPluginEntry> getGuiPlugins() { return Collections.unmodifiableList(guiPlugins); }
    public Set<String> getDetectedAddresses() { return Collections.unmodifiableSet(detectedAddresses); }
    public Set<String> getDetectedGameAddresses() { return Collections.unmodifiableSet(detectedGameAddresses); }
    public List<ServerLogData.WorldEntry> getWorlds() { return Collections.unmodifiableList(worlds); }
    public EventBus getEvents() { return events; }
    public List<Detection> getDetections() { return Collections.unmodifiableList(detections); }

    /**
     * Get the merged confidence for a specific plugin (0.0-1.0).
     * Returns 0 if plugin not detected.
     */
    public double getPluginConfidence(String pluginName) {
        return pluginConfidence.getOrDefault(pluginName, 0.0);
    }

    /**
     * Get all plugins sorted by confidence (highest first).
     */
    public List<Map.Entry<String, Double>> getPluginsByConfidence() {
        return pluginConfidence.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
    }

    /**
     * Adds a plugin from a specific detection method with confidence.
     * Merges confidence if plugin was already detected by another method.
     */
    public void addDetection(Detection detection) {
        if (detection == null || detection.value() == null || detection.value().isBlank()) return;
        detections.add(detection);

        String name = detection.value();
        pluginConfidence.merge(name, detection.confidence(), Detection::mergeConfidence);

        if (plugins.add(name)) {
            String pct = String.format("%.0f%%", detection.confidence() * 100);
            events.post(new LogEvent(LogEvent.Type.PLUGIN,
                    "Detected: " + name + " [" + detection.type().name() + " " + pct + "]"));
        }
    }

    /**
     * Adds a plugin name with default confidence (backward compat).
     * Uses MANUAL type at 1.0 confidence.
     */
    public void addPlugin(String name) {
        if (name == null || name.isBlank()) return;
        addDetection(Detection.of(name, DetectionType.MANUAL, "legacy addPlugin"));
    }

    public void addGuiPlugin(ServerLogData.GuiPluginEntry entry) {
        if (entry == null) return;
        guiPlugins.add(entry);
    }

    public void addDetectedAddress(String address) {
        if (address == null || address.isBlank()) return;
        detectedAddresses.add(address);
    }

    public void addDetectedGameAddress(String address) {
        if (address == null || address.isBlank()) return;
        detectedGameAddresses.add(address);
    }

    public void addWorld(String dimension, String resourcePack) {
        String ts = LocalDate.now().format(DATE_FORMAT);
        ServerLogData.WorldEntry entry = new ServerLogData.WorldEntry(ts, dimension, resourcePack);
        worlds.add(entry);
        events.post(new LogEvent(LogEvent.Type.WORLD, "World: " + dimension));
    }

    /**
     * Discard all detection data (called on proxy transfer).
     * Keeps connection metadata but clears plugins and detections.
     */
    public void discardDetections() {
        plugins.clear();
        pluginConfidence.clear();
        detections.clear();
        guiPlugins.clear();
        events.post(new LogEvent(LogEvent.Type.SYSTEM, "Detection data discarded (server transfer)"));
    }

    public ServerLogData toLogData() {
        String ts = LocalDate.now().format(DATE_FORMAT);
        ServerLogData.ServerInfo info = new ServerLogData.ServerInfo(
                ip, port, domain, brand, version, playerCount, maxPlayers
        );
        List<ServerLogData.PluginEntry> pluginEntries = plugins.stream()
                .sorted()
                .map(ServerLogData.PluginEntry::new)
                .toList();
        return new ServerLogData(
                ts, info, pluginEntries, List.copyOf(guiPlugins),
                List.copyOf(detectedAddresses), List.copyOf(detectedGameAddresses),
                List.copyOf(worlds)
        );
    }

    public void reset() {
        ip = "unknown";
        port = 25565;
        domain = "unknown";
        brand = "unknown";
        version = "unknown";
        serverSoftware = "";
        dimension = "minecraft:overworld";
        resourcePack = null;
        playerCount = 0;
        maxPlayers = 0;
        plugins.clear();
        pluginConfidence.clear();
        detections.clear();
        guiPlugins.clear();
        detectedAddresses.clear();
        detectedGameAddresses.clear();
        worlds.clear();
    }
}
