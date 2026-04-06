package com.archivist.data;

import com.archivist.detection.Detection;
import com.archivist.detection.DetectionConstants;
import com.archivist.detection.DetectionType;
import com.archivist.detection.PluginGlossary;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private volatile int maxPlayers = -1;
    private PluginGlossary glossary;

    private final Set<String> plugins = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<ServerLogData.GuiPluginEntry> guiPlugins = new CopyOnWriteArrayList<>();
    private final Set<String> detectedAddresses = ConcurrentHashMap.newKeySet();
    private final Set<String> detectedGameAddresses = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<ServerLogData.WorldEntry> worlds = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Detection> detections = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Double> pluginConfidence = new ConcurrentHashMap<>();

    private final EventBus events;

    public ServerSession(EventBus events) {
        this.events = events;
    }

    public void setGlossary(PluginGlossary glossary) {
        this.glossary = glossary;
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

    public double getPluginConfidence(String pluginName) {
        return pluginConfidence.getOrDefault(pluginName, 0.0);
    }

    public List<Map.Entry<String, Double>> getPluginsByConfidence() {
        return pluginConfidence.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
    }

    public void boostConfidence(String pluginName, double floor) {
        pluginConfidence.computeIfPresent(pluginName, (k, current) -> Math.max(current, floor));
    }

    public void addDetection(Detection detection) {
        if (detection == null || detection.value() == null || detection.value().isBlank()) return;
        String name = detection.value().toLowerCase(java.util.Locale.ROOT);
        if (name.length() < 3) return;
        if (name.contains(" ") || name.contains("/")) return;
        boolean inGlossary = glossary != null && glossary.contains(name);
        if (name.length() < 5 && !inGlossary) return;
        if (DetectionConstants.COMMON_WORDS.contains(name) && !inGlossary) return;

        for (String existing : plugins) {
            if (name.startsWith(existing)) return;
        }
        List<String> toRemove = new ArrayList<>();
        for (String existing : plugins) {
            if (existing.startsWith(name)) toRemove.add(existing);
        }
        for (String r : toRemove) {
            plugins.remove(r);
            pluginConfidence.remove(r);
        }

        Detection norm = new Detection(name, detection.type(), detection.confidence(), detection.source());
        detections.add(norm);
        pluginConfidence.merge(name, norm.confidence(), Detection::mergeConfidence);

        if (plugins.add(name)) {
            String pct = String.format("%.0f%%", norm.confidence() * 100);
            events.post(new LogEvent(LogEvent.Type.PLUGIN,
                    "Detected: " + name + " [" + norm.type().name() + " " + pct + "]"));
        }
    }

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
