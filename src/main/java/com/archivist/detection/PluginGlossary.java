package com.archivist.detection;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PluginGlossary {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final String BUNDLED_PATH = "/assets/archivist/glossary.json";
    private static final String GLOSSARY_DIR = "archivist/glossary";
    private static final String CUSTOM_FILE = "custom.json";
    private static final String UNRESOLVED_FILE = "archivist/unresolved_namespaces.json";
    private static final int MAX_EDIT_DISTANCE = 2;

    private volatile Map<String, String> entries = new ConcurrentHashMap<>();
    private final Map<String, String> userOverrides = new ConcurrentHashMap<>();
    private final Set<String> unresolvedNamespaces = ConcurrentHashMap.newKeySet();
    private final Set<String> removedKeys = ConcurrentHashMap.newKeySet();

    public void load() {
        Map<String, String> newEntries = new ConcurrentHashMap<>();
        Map<String, String> old = entries;
        entries = newEntries;
        userOverrides.clear();
        removedKeys.clear();
        loadBundled();
        loadGlossaryFolder();
        LOGGER.info("PluginGlossary loaded {} entries", entries.size());
        loadUnresolved();
    }

    public Optional<String> resolve(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(entries.get(key.toLowerCase(Locale.ROOT)));
    }

    public boolean contains(String key) {
        if (key == null) return false;
        return entries.containsKey(key.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> getAllEntries() {
        return Map.copyOf(entries);
    }

    public boolean isCustomEntry(String key) {
        if (key == null) return false;
        return userOverrides.containsKey(key.toLowerCase(Locale.ROOT));
    }

    public Optional<String> resolveFuzzy(String key) {
        if (key == null) return Optional.empty();
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.length() < 5) return Optional.empty();
        String bestKey = null;
        int bestDist = MAX_EDIT_DISTANCE + 1;
        for (String candidate : entries.keySet()) {
            if (Math.abs(candidate.length() - lower.length()) > MAX_EDIT_DISTANCE) continue;
            int dist = levenshtein(lower, candidate);
            if (dist > 0 && dist < bestDist) {
                bestDist = dist;
                bestKey = candidate;
            }
        }
        return bestKey != null ? Optional.of(entries.get(bestKey)) : Optional.empty();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    public void trackUnresolved(String namespace) {
        if (namespace != null && !namespace.isEmpty()) {
            unresolvedNamespaces.add(namespace.toLowerCase(Locale.ROOT));
        }
    }

    public Set<String> getUnresolvedNamespaces() {
        return Set.copyOf(unresolvedNamespaces);
    }

    public void clearUnresolved(String namespace) {
        unresolvedNamespaces.remove(namespace.toLowerCase(Locale.ROOT));
    }

    public void loadUnresolved() {
        Path configDir = FabricLoader.getInstance().getGameDir();
        Path file = configDir.resolve(UNRESOLVED_FILE);
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            var arr = root.getAsJsonArray("namespaces");
            if (arr != null) {
                for (var el : arr) {
                    unresolvedNamespaces.add(el.getAsString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load unresolved namespaces", e);
        }
    }

    public void saveUnresolved() {
        Path configDir = FabricLoader.getInstance().getGameDir();
        Path file = configDir.resolve(UNRESOLVED_FILE);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            var arr = new com.google.gson.JsonArray();
            for (String ns : unresolvedNamespaces) {
                arr.add(ns);
            }
            root.add("namespaces", arr);
            Files.writeString(file, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed to save unresolved namespaces", e);
        }
    }

    public void addUserOverride(String alias, String canonicalName) {
        String key = alias.toLowerCase(Locale.ROOT);
        String val = canonicalName.toLowerCase(Locale.ROOT);
        entries.put(key, val);
        userOverrides.put(key, val);
        removedKeys.remove(key);
        unresolvedNamespaces.remove(key);
        saveUserOverrides();
    }

    public void removeUserOverride(String alias) {
        String key = alias.toLowerCase(Locale.ROOT);
        if (userOverrides.containsKey(key)) {
            userOverrides.remove(key);
            entries.remove(key);
        } else {
            removedKeys.add(key);
            entries.remove(key);
        }
        saveUserOverrides();
    }

    public Map<String, String> getUserOverrides() {
        return Map.copyOf(userOverrides);
    }

    private void saveUserOverrides() {
        Path configDir = FabricLoader.getInstance().getGameDir();
        Path file = configDir.resolve(GLOSSARY_DIR).resolve(CUSTOM_FILE);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject entriesObj = new JsonObject();
            for (var entry : userOverrides.entrySet()) {
                entriesObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("entries", entriesObj);
            com.google.gson.JsonArray removedArr = new com.google.gson.JsonArray();
            for (String rk : removedKeys) {
                removedArr.add(rk);
            }
            root.add("removed", removedArr);
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("Failed to save user glossary overrides", e);
        }
    }

    private void loadBundled() {
        try (InputStream is = PluginGlossary.class.getResourceAsStream(BUNDLED_PATH)) {
            if (is == null) {
                LOGGER.warn("Bundled glossary not found at {}", BUNDLED_PATH);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                parseAndMerge(reader);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load bundled glossary", e);
        }
    }

    private void loadGlossaryFolder() {
        Path configDir = FabricLoader.getInstance().getGameDir();
        Path glossaryDir = configDir.resolve(GLOSSARY_DIR);

        try {
            Files.createDirectories(glossaryDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create glossary directory", e);
            return;
        }

        Path oldFile = configDir.resolve("archivist/glossary.json");
        if (Files.exists(oldFile)) {
            try {
                Files.move(oldFile, glossaryDir.resolve(CUSTOM_FILE));
            } catch (Exception ignored) {}
        }

        try (InputStream is = PluginGlossary.class.getResourceAsStream(BUNDLED_PATH)) {
            if (is != null) {
                Path bundledTarget = glossaryDir.resolve("bundled.json");
                Files.copy(is, bundledTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to copy bundled glossary to folder", e);
        }

        List<Path> jsonFiles;
        try (Stream<Path> stream = Files.list(glossaryDir)) {
            jsonFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        String nameA = a.getFileName().toString();
                        String nameB = b.getFileName().toString();
                        if (nameA.equals("bundled.json")) return -1;
                        if (nameB.equals("bundled.json")) return 1;
                        if (nameA.equals(CUSTOM_FILE)) return 1;
                        if (nameB.equals(CUSTOM_FILE)) return -1;
                        return nameA.compareTo(nameB);
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to list glossary folder", e);
            return;
        }

        for (Path jsonFile : jsonFiles) {
            String fileName = jsonFile.getFileName().toString();
            if (fileName.equals(CUSTOM_FILE)) {
                try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject entriesObj = root.getAsJsonObject("entries");
                    if (entriesObj != null) {
                        for (Map.Entry<String, JsonElement> entry : entriesObj.entrySet()) {
                            String key = entry.getKey().toLowerCase(Locale.ROOT);
                            String value = entry.getValue().getAsString();
                            entries.put(key, value);
                            userOverrides.put(key, value);
                        }
                    }
                    var removedArr = root.getAsJsonArray("removed");
                    if (removedArr != null) {
                        for (var el : removedArr) {
                            String rk = el.getAsString().toLowerCase(Locale.ROOT);
                            removedKeys.add(rk);
                            entries.remove(rk);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load custom glossary from {}", jsonFile, e);
                }
            } else {
                try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
                    parseAndMerge(reader);
                } catch (Exception e) {
                    LOGGER.error("Failed to load glossary file {}", jsonFile, e);
                }
            }
        }
    }

    public static void openGlossaryFolder() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("glossary");
            Files.createDirectories(dir);
            Runtime.getRuntime().exec(new String[]{"explorer", dir.toAbsolutePath().toString()});
        } catch (Exception ignored) {}
    }

    private void parseAndMerge(BufferedReader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonObject entriesObj = root.getAsJsonObject("entries");
        if (entriesObj == null) return;

        for (Map.Entry<String, JsonElement> entry : entriesObj.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().getAsString();
            entries.put(key, value);
        }
    }
}
