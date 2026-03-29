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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates all detection methods and merges results into the ServerSession.
 * All results carry confidence levels and are adjusted by session confidence (lobby detection).
 */
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

    private final Set<String> commandTreePlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> allDetectedPlugins = ConcurrentHashMap.newKeySet();

    public DetectionPipeline(PluginGlossary glossary) {
        this.glossary = glossary;
        this.commandTreeScanner = new CommandTreeScanner(glossary);
        this.namespaceDetector = new NamespaceDetector(glossary);
        this.brandDetector = new BrandDetector();
    }

    public SessionConfidence getSessionConfidence() { return sessionConfidence; }

    public void onServerJoin(ServerSession session) {
        reset();
        this.session = session;
        sessionConfidence.reset();
    }

    /**
     * Called when domain is resolved — feeds into session confidence for lobby detection.
     */
    public void onDomainResolved(String domain) {
        sessionConfidence.onDomainKnown(domain);
    }

    public void onCommandTree(CommandDispatcher<?> dispatcher) {
        // Lobby detection: second command tree MAY indicate proxy transfer
        boolean isTransfer = sessionConfidence.onCommandTree();

        if (isTransfer) {
            // Discard previous detection data on transfer so the new server is evaluated fresh
            LOGGER.info("Proxy transfer detected — discarding previous detection data");
            if (session != null) {
                session.discardDetections();
            }
            commandTreePlugins.clear();
            allDetectedPlugins.clear();
            scanComplete = false;
            commandTreeScanner.reset();
            namespaceDetector.reset();
        }

        CommandTreeScanner.CommandTreeResult result = commandTreeScanner.processCommandTree(dispatcher);
        commandTreePlugins.addAll(result.plugins());

        // Feed raw command NAMES (not resolved plugins) into session confidence for lobby heuristics
        Set<String> rawCommandNames = new java.util.HashSet<>();
        for (var node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            if (name != null) rawCommandNames.add(name.toLowerCase(Locale.ROOT));
        }
        sessionConfidence.onCommandsDetected(rawCommandNames);

        finishScan();
    }

    public void onNamespace(String namespace) { namespaceDetector.onNamespace(namespace); }
    public void onChannelNamespace(String namespace) { onNamespace(namespace); }
    public void onRegistryNamespace(String namespace) { onNamespace(namespace); }

    public void onBrandReceived(String rawBrand) {
        if (session == null) return;
        if (rawBrand != null && rawBrand.equals(lastBrand)) return;
        lastBrand = rawBrand;
        BrandDetector.BrandResult result = brandDetector.parseBrand(rawBrand);
        session.setBrand(rawBrand);
        sessionConfidence.onBrandReceived(rawBrand);
        if (result.serverSoftware() != null) {
            session.setServerSoftware(result.serverSoftware());
            session.addDetection(Detection.of(result.serverSoftware(),
                    DetectionType.BRAND, "brand: " + rawBrand));
            if (result.softwareVersion() != null) {
                session.setVersion(result.softwareVersion());
            }
        }
        session.getEvents().post(new LogEvent(LogEvent.Type.BRAND, "Server brand: " + rawBrand));
    }

    /**
     * Processes tab list header/footer — feeds to both URL extraction and lobby detection.
     */
    public void onTabList(String header, String footer) {
        sessionConfidence.onTabList(header, footer);
        if (header != null) onChatMessage(header);
        if (footer != null) onChatMessage(footer);
    }

    public void onPlayerCount(int advertisedCount, int tabListCount) {
        sessionConfidence.onPlayerCount(advertisedCount, tabListCount);
    }

    public void onChatMessage(String message) {
        if (session == null || message == null) return;
        sessionConfidence.onChatMessage(message);
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

    /**
     * Merges all detection sources with confidence levels and writes to the session.
     */
    public synchronized void finishScan() {
        if (scanComplete) return;
        scanComplete = true;

        namespaceDetector.setImmediateMode(true);
        Set<String> namespacePlugins = namespaceDetector.processPending();
        double sessionConf = sessionConfidence.getConfidence();

        // Emit detections with confidence from each source
        if (session != null) {
            // Command tree detections (already in commandTreePlugins)
            for (String plugin : commandTreePlugins) {
                String resolved = glossary.resolve(plugin.toLowerCase(Locale.ROOT)).orElse(plugin);
                boolean namespaced = plugin.contains(":");
                DetectionType type = namespaced ? DetectionType.COMMAND_TREE_NAMESPACED : DetectionType.COMMAND_TREE_GLOSSARY;
                double rawConf = type.defaultConfidence();
                double effective = sessionConf * rawConf;
                session.addDetection(Detection.of(resolved, type, effective, "cmd: " + plugin));
                allDetectedPlugins.add(resolved);
            }

            // Namespace detections (channels + registry merged)
            for (String plugin : namespacePlugins) {
                // We don't know if it was channel or registry, use channel confidence (higher)
                double effective = sessionConf * DetectionType.CHANNEL.defaultConfidence();
                session.addDetection(Detection.of(plugin, DetectionType.CHANNEL, effective, "namespace"));
                allDetectedPlugins.add(plugin);
            }

            String label = sessionConf > 0.7 ? "REAL SERVER" : sessionConf > 0.4 ? "UNCERTAIN" : "LOBBY";
            session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                    "Scan complete: " + allDetectedPlugins.size() + " plugins"
                            + " | Confidence: " + String.format("%.0f%%", sessionConf * 100) + " (" + label + ")"));

            // Log key lobby/server signals
            for (var s : sessionConfidence.getLobbySignals()) {
                session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                        "  \u2193 " + String.format("-%.0f%%", s.weight() * 100) + " " + s.reason()));
            }
            for (var s : sessionConfidence.getServerSignals()) {
                session.getEvents().post(new LogEvent(LogEvent.Type.SYSTEM,
                        "  \u2191 " + String.format("+%.0f%%", s.weight() * 100) + " " + s.reason()));
            }
        }

        LOGGER.info("Detection scan complete: {} plugins, session confidence: {:.2f}",
                allDetectedPlugins.size(), sessionConf);
    }

    public boolean isScanComplete() { return scanComplete; }
    public Set<String> getAllDetectedPlugins() { return Set.copyOf(allDetectedPlugins); }

    /**
     * Re-scans using the cached command dispatcher and brand. Unlike reset()+onServerJoin(),
     * this re-processes data that was already received so results are not lost.
     */
    public void rescan(ServerSession session, CommandDispatcher<?> dispatcher) {
        // Clear detection results but keep the session reference
        scanComplete = false;
        this.session = session;
        commandTreePlugins.clear();
        allDetectedPlugins.clear();
        commandTreeScanner.reset();
        namespaceDetector.reset();
        // Do NOT reset sessionConfidence — lobby detection signals are still valid

        if (session != null) {
            session.discardDetections();
        }

        // Re-process command tree if available
        if (dispatcher != null) {
            CommandTreeScanner.CommandTreeResult result = commandTreeScanner.processCommandTree(dispatcher);
            commandTreePlugins.addAll(result.plugins());

            Set<String> rawCommandNames = new java.util.HashSet<>();
            for (var node : dispatcher.getRoot().getChildren()) {
                String name = node.getName();
                if (name != null) rawCommandNames.add(name.toLowerCase(Locale.ROOT));
            }
            sessionConfidence.onCommandsDetected(rawCommandNames);
        }

        // Re-process brand if cached in session
        if (session != null && session.getBrand() != null) {
            BrandDetector.BrandResult result = brandDetector.parseBrand(session.getBrand());
            if (result.serverSoftware() != null) {
                session.setServerSoftware(result.serverSoftware());
                session.addDetection(Detection.of(result.serverSoftware(),
                        DetectionType.BRAND, "brand: " + session.getBrand()));
                if (result.softwareVersion() != null) {
                    session.setVersion(result.softwareVersion());
                }
            }
        }

        finishScan();
    }

    public void reset() {
        scanComplete = false;
        session = null;
        lastBrand = null;
        commandTreePlugins.clear();
        allDetectedPlugins.clear();
        commandTreeScanner.reset();
        namespaceDetector.reset();
        sessionConfidence.reset();
    }
}
