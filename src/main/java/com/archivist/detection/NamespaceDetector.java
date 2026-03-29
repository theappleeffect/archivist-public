package com.archivist.detection;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects plugins from namespace strings (plugin channels and registry data).
 * Replaces the separate ChannelDetector and RegistryDetector.
 */
public final class NamespaceDetector {

    private final PluginGlossary glossary;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> detected = ConcurrentHashMap.newKeySet();
    private volatile boolean immediateMode;

    public NamespaceDetector(PluginGlossary glossary) {
        this.glossary = glossary;
    }

    /**
     * Feed a namespace from any source (channel, registry, etc).
     * If immediateMode is on, resolves right away; otherwise queues.
     */
    public synchronized void onNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) return;
        String lower = namespace.toLowerCase(Locale.ROOT);
        if (DetectionConstants.IGNORED_NAMESPACES.contains(lower)) return;

        if (immediateMode) {
            detected.add(glossary.resolve(lower).orElse(lower));
        } else {
            pending.add(lower);
        }
    }

    /**
     * Resolve all queued namespaces through the glossary.
     */
    public Set<String> processPending() {
        Set<String> resolved = new LinkedHashSet<>();
        for (String ns : pending) {
            String name = glossary.resolve(ns).orElse(ns);
            resolved.add(name);
            detected.add(name);
        }
        pending.clear();
        return resolved;
    }

    public Set<String> getDetected() { return Set.copyOf(detected); }
    public synchronized void setImmediateMode(boolean immediate) {
        this.immediateMode = immediate;
        if (immediate) {
            processPending();
        }
    }

    public void reset() {
        pending.clear();
        detected.clear();
        immediateMode = false;
    }
}
