package com.archivist.detection;

import com.archivist.data.EventBus;
import com.archivist.data.LogEvent;
import com.archivist.data.ServerSession;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DetectionPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    private final PluginGlossary glossary;
    private final CommandTreeScanner commandTreeScanner;
    private final NamespaceDetector namespaceDetector;
    private final BrandDetector brandDetector;
    private final SessionConfidence sessionConfidence = new SessionConfidence();

    private volatile ServerSession session;
    private volatile boolean scanComplete;
    private volatile String lastBrand;
    private volatile boolean chatUrlExtractionEnabled = true;

    private final Set<String> commandTreePlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> commandTreeGlossaryPlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> allDetectedPlugins = ConcurrentHashMap.newKeySet();
    private final List<String> chatMessageBuffer = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String versionCommand;

    public DetectionPipeline(PluginGlossary glossary) {
        this.glossary = glossary;
        this.commandTreeScanner = new CommandTreeScanner(glossary);
        this.namespaceDetector = new NamespaceDetector(glossary);
        this.brandDetector = new BrandDetector();
    }

    public SessionConfidence getSessionConfidence() { return sessionConfidence; }

    public void setChatUrlExtractionEnabled(boolean enabled) {
        this.chatUrlExtractionEnabled = enabled;
    }

    public void onServerJoin(ServerSession session) {
        reset();
        this.session = session;
        sessionConfidence.reset();
    }

    public void onDomainResolved(String domain) {
        sessionConfidence.onDomainKnown(domain);
    }

    public void onCommandTree(CommandDispatcher<?> dispatcher) {
        boolean isTransfer = sessionConfidence.onCommandTree();

        if (isTransfer) {
            LOGGER.info("Proxy transfer detected — discarding previous detection data");
            if (session != null) {
                session.discardDetections();
            }
            commandTreePlugins.clear();
            commandTreeGlossaryPlugins.clear();
            allDetectedPlugins.clear();
            scanComplete = false;
            commandTreeScanner.reset();
            namespaceDetector.reset();
        }

        CommandTreeScanner.CommandTreeResult result = commandTreeScanner.processCommandTree(dispatcher);
        commandTreePlugins.addAll(result.namespacedPlugins());
        commandTreeGlossaryPlugins.addAll(result.glossaryPlugins());
        if (result.versionAliasFound()) {
            versionCommand = result.versionAliasCommand();
        }

        Set<String> rawCommandNames = new java.util.HashSet<>();
        for (var node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (name != null) rawCommandNames.add(name.toLowerCase(Locale.ROOT));
        }
        sessionConfidence.onCommandsDetected(rawCommandNames);

        finishScan();
    }

    public void onNamespace(String namespace) {
        namespaceDetector.onNamespace(namespace);
        if (scanComplete && session != null && namespace != null) {
            String lower = namespace.toLowerCase(Locale.ROOT);
            if (!DetectionConstants.IGNORED_NAMESPACES.contains(lower)) {
                String resolved = glossary.resolve(lower)
                        .or(() -> glossary.resolveFuzzy(lower))
                        .orElse(lower);
                allDetectedPlugins.add(resolved);
                session.addDetection(Detection.of(resolved, DetectionType.CHANNEL,
                        DetectionType.CHANNEL.defaultConfidence(), "late-namespace"));
            }
        }
    }
    private static final Set<String> BRAND_NAMESPACES = Set.of(
            "velocity", "bungeecord", "waterfall", "paper", "purpur", "spigot", "folia", "pufferfish");

    public void onChannelNamespace(String namespace) {
        if (namespace != null && BRAND_NAMESPACES.contains(namespace.toLowerCase(java.util.Locale.ROOT))) {
            if (session != null && session.getServerSoftware() == null) {
                String name = namespace.substring(0, 1).toUpperCase() + namespace.substring(1).toLowerCase();
                session.setServerSoftware(name);
                if (session.getBrand() == null || session.getBrand().isEmpty()) {
                    session.setBrand(name);
                }
            }
            return;
        }
        onNamespace(namespace);
    }
    public void onRegistryNamespace(String namespace) {
        onChannelNamespace(namespace);
    }

    public void onBrandReceived(String rawBrand) {
        if (session == null) return;
        if (rawBrand != null && rawBrand.equals(lastBrand)) return;
        lastBrand = rawBrand;
        BrandDetector.BrandResult result = brandDetector.parseBrand(rawBrand);
        session.setBrand(rawBrand);
        sessionConfidence.onBrandReceived(rawBrand);
        if (result.serverSoftware() != null) {
            session.setServerSoftware(result.serverSoftware());
            if (result.softwareVersion() != null) {
                session.setVersion(result.softwareVersion());
            }
        }
        session.getEvents().post(new LogEvent(LogEvent.Type.BRAND, "Server brand: " + rawBrand));
    }

    public void onTabList(String header, String footer) {
        sessionConfidence.onTabList(header, footer);
        if (header != null) extractUrls(header);
        if (footer != null) extractUrls(footer);
    }

    public void onPlayerCount(int advertisedCount, int tabListCount) {
        sessionConfidence.onPlayerCount(advertisedCount, tabListCount);
    }

    public List<String> getChatMessageBuffer() { return chatMessageBuffer; }

    public void onChatMessage(String message) {
        if (session == null || message == null) return;
        chatMessageBuffer.add(message);
        sessionConfidence.onChatMessage(message);
        if (awaitingVersionResponse) {
            onVersionResponse(message);
        }
        if (chatUrlExtractionEnabled) {
            extractUrls(message);
        }
    }

    private void extractUrls(String message) {
        if (session == null || message == null) return;
        UrlExtractor.CategorizedResult result = UrlExtractor.extract(message);

        for (String url : result.detectedUrls()) {
            session.addDetectedAddress(url);
        }
        for (String addr : result.gameAddresses()) {
            session.addDetectedGameAddress(addr);
        }
        for (String hp : result.highPriorityMatches()) {
            session.addDetectedGameAddress(hp);
        }
    }

    public void onGamemodeReceived(String gamemode) {
        sessionConfidence.onGamemode(gamemode);
    }

    public void tick() {
        sessionConfidence.tick();
    }

    public synchronized void finishScan() {
        if (scanComplete) return;
        scanComplete = true;

        namespaceDetector.setImmediateMode(true);
        Set<String> namespacePlugins = namespaceDetector.processPending();
        double sessionConf = sessionConfidence.getConfidence();

        if (session != null) {
            boolean isLobby = sessionConf <= 0.4;

            if (!isLobby) {
                for (String plugin : commandTreePlugins) {
                    String resolved = glossary.resolve(plugin.toLowerCase(Locale.ROOT))
                            .or(() -> glossary.resolveFuzzy(plugin.toLowerCase(Locale.ROOT)))
                            .orElse(plugin);
                    resolved = resolved.toLowerCase(Locale.ROOT);
                    session.addDetection(Detection.of(resolved, DetectionType.COMMAND_TREE_NAMESPACED,
                            DetectionType.COMMAND_TREE_NAMESPACED.defaultConfidence(), "cmd: " + plugin));
                    allDetectedPlugins.add(resolved);
                }

                for (String plugin : commandTreeGlossaryPlugins) {
                    String resolved = plugin.toLowerCase(Locale.ROOT);
                    session.addDetection(Detection.of(resolved, DetectionType.COMMAND_TREE_GLOSSARY,
                            DetectionType.COMMAND_TREE_GLOSSARY.defaultConfidence(), "cmd-glossary: " + plugin));
                    allDetectedPlugins.add(resolved);
                }

                for (String plugin : namespacePlugins) {
                    String resolved = plugin.toLowerCase(Locale.ROOT);
                    session.addDetection(Detection.of(resolved, DetectionType.CHANNEL,
                            DetectionType.CHANNEL.defaultConfidence(), "namespace"));
                    allDetectedPlugins.add(resolved);
                }

                Map<String, Set<DetectionType>> sourceTypes = new java.util.HashMap<>();
                for (String plugin : commandTreePlugins) {
                    String resolved = glossary.resolve(plugin.toLowerCase(Locale.ROOT)).orElse(plugin);
                    sourceTypes.computeIfAbsent(resolved, k -> new java.util.HashSet<>())
                            .add(DetectionType.COMMAND_TREE_NAMESPACED);
                }
                for (String plugin : commandTreeGlossaryPlugins) {
                    sourceTypes.computeIfAbsent(plugin.toLowerCase(Locale.ROOT), k -> new java.util.HashSet<>())
                            .add(DetectionType.COMMAND_TREE_GLOSSARY);
                }
                for (String plugin : namespacePlugins) {
                    sourceTypes.computeIfAbsent(plugin, k -> new java.util.HashSet<>())
                            .add(DetectionType.CHANNEL);
                }
                for (var entry : sourceTypes.entrySet()) {
                    if (entry.getValue().size() >= 2) {
                        session.boostConfidence(entry.getKey(), 0.60);
                    }
                }
            }

            String label = isLobby ? "LOBBY (brand only)" : (sessionConf > 0.7 ? "REAL SERVER" : "UNCERTAIN");
            session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                    "Scan complete: " + allDetectedPlugins.size() + " plugins"
                            + " | Confidence: " + String.format("%.0f%%", sessionConf * 100) + " (" + label + ")"));

            for (var s : sessionConfidence.getLobbySignals()) {
                session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                        "  \u2193 " + String.format("-%.0f%%", s.weight() * 100) + " " + s.reason()));
            }
            for (var s : sessionConfidence.getServerSignals()) {
                session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                        "  \u2191 " + String.format("+%.0f%%", s.weight() * 100) + " " + s.reason()));
            }
        }

        LOGGER.info("Detection scan complete: {} plugins, session confidence: {}",
                allDetectedPlugins.size(), String.format("%.2f", sessionConf));
    }

    public boolean isScanComplete() { return scanComplete; }
    public Set<String> getAllDetectedPlugins() { return Set.copyOf(allDetectedPlugins); }

    public void rescan(ServerSession session, CommandDispatcher<?> dispatcher) {
        scanComplete = false;
        this.session = session;
        commandTreePlugins.clear();
        commandTreeGlossaryPlugins.clear();
        allDetectedPlugins.clear();
        commandTreeScanner.reset();
        namespaceDetector.reset();

        if (session != null) {
            session.discardDetections();
        }

        if (dispatcher != null) {
            CommandTreeScanner.CommandTreeResult result = commandTreeScanner.processCommandTree(dispatcher);
            commandTreePlugins.addAll(result.namespacedPlugins());
            commandTreeGlossaryPlugins.addAll(result.glossaryPlugins());
            if (result.versionAliasFound()) {
                versionCommand = result.versionAliasCommand();
            }

            Set<String> rawCommandNames = new java.util.HashSet<>();
            for (var node : dispatcher.getRoot().getChildren()) {
                String name = node.getName();
                if (name != null) rawCommandNames.add(name.toLowerCase(Locale.ROOT));
            }
            sessionConfidence.onCommandsDetected(rawCommandNames);
        }

        if (session != null && session.getBrand() != null) {
            BrandDetector.BrandResult result = brandDetector.parseBrand(session.getBrand());
            if (result.serverSoftware() != null) {
                session.setServerSoftware(result.serverSoftware());
                if (result.softwareVersion() != null) {
                    session.setVersion(result.softwareVersion());
                }
            }
        }

        finishScan();
    }

    private static final Pattern VERSION_RESPONSE_PATTERN = Pattern.compile(
            "(?:running|version)[:\\s]+(\\S+)\\s+(?:version\\s+)?(.+?)(?:\\s*\\(MC:.*\\))?$",
            Pattern.CASE_INSENSITIVE
    );

    private volatile boolean awaitingVersionResponse = false;

    public void startVersionProbe() { awaitingVersionResponse = true; }

    public void onVersionResponse(String message) {
        if (!awaitingVersionResponse || session == null || message == null) return;
        Matcher m = VERSION_RESPONSE_PATTERN.matcher(message);
        if (m.find()) {
            String software = m.group(1);
            String version = m.group(2).trim();
            if (session.getServerSoftware() == null || session.getServerSoftware().isEmpty()) {
                session.setServerSoftware(software);
            }
            if (version != null && !version.isEmpty()) {
                session.setVersion(version);
            }
            session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                    "Version probe: " + software + " " + version));
            awaitingVersionResponse = false;
        }
    }

    public void reset() {
        glossary.saveUnresolved();
        scanComplete = false;
        session = null;
        lastBrand = null;
        chatUrlExtractionEnabled = true;
        chatMessageBuffer.clear();
        versionCommand = null;
        awaitingVersionResponse = false;
        commandTreePlugins.clear();
        commandTreeGlossaryPlugins.clear();
        allDetectedPlugins.clear();
        commandTreeScanner.reset();
        namespaceDetector.reset();
        sessionConfidence.reset();
    }

    public String getVersionCommand() { return versionCommand; }
}
