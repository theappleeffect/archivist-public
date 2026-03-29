package com.archivist.gui.render;

import com.archivist.ArchivistMod;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.*;
import java.util.*;

/**
 * Manages color themes. Loads bundled JSON themes from resources and custom
 * themes from .minecraft/archivist/themes/. Java themes are also registered.
 */
public class ThemeManager {

    private static ThemeManager instance;
    private final Map<String, ColorScheme> themes = new LinkedHashMap<>();
    private boolean loaded = false;

    public static ThemeManager getInstance() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }

    /** Lazy-load all themes on first access. */
    public Map<String, ColorScheme> getThemes() {
        if (!loaded) load();
        return Collections.unmodifiableMap(themes);
    }

    public ColorScheme getTheme(String name) {
        if (!loaded) load();
        return themes.get(name.toLowerCase(Locale.ROOT));
    }

    public void load() {
        themes.clear();

        loadBundledThemes();
        loadCustomThemes();

        themes.put("rainbow", new RainbowTheme(RainbowTheme.Variant.RAINBOW));

        loaded = true;
    }

    private void loadBundledThemes() {
        String[] bundled = {
                "aurora", "blue", "ember", "midnight", "monochrome", "monochrome_plus", "red", "slate", "sunset"
        };
        for (String name : bundled) {
            try (InputStream is = ThemeManager.class.getResourceAsStream("/assets/archivist/themes/" + name + ".json")) {
                if (is != null) {
                    JsonObject obj = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                    ColorScheme theme = parseJsonTheme(obj);
                    if (theme != null) {
                        themes.put(theme.name().toLowerCase(Locale.ROOT), theme);
                    }
                }
            } catch (Exception e) {
                ArchivistMod.LOGGER.warn("[Archivist] Failed to load bundled theme {}: {}", name, e.getMessage());
            }
        }
    }

    private void loadCustomThemes() {
        Path themeDir = FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("themes");
        if (!Files.isDirectory(themeDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(themeDir, "*.json")) {
            for (Path file : stream) {
                try (Reader r = Files.newBufferedReader(file)) {
                    JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                    ColorScheme theme = parseJsonTheme(obj);
                    if (theme != null) {
                        themes.put(theme.name().toLowerCase(Locale.ROOT), theme);
                    }
                } catch (Exception e) {
                    ArchivistMod.LOGGER.warn("[Archivist] Failed to load theme {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("[Archivist] Failed to scan theme dir: {}", e.getMessage());
        }
    }

    /** Reload all themes (for live preview). */
    public void reload() {
        loaded = false;
    }

    /** Parse a JSON object into a JsonTheme. Supports simplified 3-color format. */
    public static ColorScheme parseJsonTheme(JsonObject obj) {
        if (!obj.has("name")) return null;

        // Simplified format: {"name":"...", "accent":"#...", "background":"#...", "text":"#..."}
        if (!obj.has("colors") && obj.has("accent") && obj.has("background") && obj.has("text")) {
            String name = obj.get("name").getAsString();
            int accent = JsonTheme.parseHexColor(obj.get("accent").getAsString(), 0xFF40C0E0);
            int bg = JsonTheme.parseHexColor(obj.get("background").getAsString(), 0xFF081018);
            int text = JsonTheme.parseHexColor(obj.get("text").getAsString(), 0xFFE0E0E0);
            int bgAlpha = obj.has("bgAlpha") ? obj.get("bgAlpha").getAsInt() : -1;
            int tbAlpha = obj.has("titleBarAlpha") ? obj.get("titleBarAlpha").getAsInt() : -1;
            int ovAlpha = obj.has("overlayAlpha") ? obj.get("overlayAlpha").getAsInt() : -1;
            return ThemeGenerator.generate(name, accent, bg, text, bgAlpha, tbAlpha, ovAlpha);
        }

        return new JsonTheme(obj);
    }
}
