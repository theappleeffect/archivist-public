package com.archivist.detection;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NamespaceDetector {

    private final PluginGlossary glossary;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> detected = ConcurrentHashMap.newKeySet();
    private volatile boolean immediateMode;

    public NamespaceDetector(PluginGlossary glossary) {
        this.glossary = glossary;
    }

    public synchronized void onNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) return;
        String lower = namespace.toLowerCase(Locale.ROOT);
        if (DetectionConstants.IGNORED_NAMESPACES.contains(lower)) return;
        if (DetectionConstants.CLIENT_MOD_NAMESPACES.contains(lower)) return;

        if (immediateMode) {
            String name = glossary.resolve(lower).or(() -> glossary.resolveFuzzy(lower)).orElse(lower);
            if (name.equals(lower) && !glossary.contains(lower)) {
                glossary.trackUnresolved(lower);
            }
            detected.add(name);
        } else {
            pending.add(lower);
        }
    }

    public Set<String> processPending() {
        Set<String> resolved = new LinkedHashSet<>();
        for (String ns : pending) {
            String name = glossary.resolve(ns).or(() -> glossary.resolveFuzzy(ns)).orElse(ns);
            if (name.equals(ns) && !glossary.contains(ns)) {
                glossary.trackUnresolved(ns);
            }
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
