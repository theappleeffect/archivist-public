package com.archivist.gui.render;

import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class FontManager {

    private static final Path FONTS_DIR = FabricLoader.getInstance().getGameDir()
            .resolve("archivist").resolve("fonts");

    private static final Map<String, AWTFontRenderer> loadedFonts = new HashMap<>();

    private FontManager() {}

    public static List<String> getAvailableFonts() {
        List<String> fonts = new ArrayList<>();
        fonts.add("default");
        if (!Files.isDirectory(FONTS_DIR)) return fonts;
        try (Stream<Path> stream = Files.list(FONTS_DIR)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".ttf") || name.endsWith(".otf");
                    })
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        fonts.add(name.substring(0, name.lastIndexOf('.')));
                    });
        } catch (IOException ignored) {}
        return fonts;
    }

    public static AWTFontRenderer getRenderer(String fontName) {
        if ("default".equals(fontName) || fontName == null) return null;
        return loadedFonts.computeIfAbsent(fontName, name -> {
            Path fontPath = FONTS_DIR.resolve(name + ".ttf");
            if (!Files.exists(fontPath)) {
                fontPath = FONTS_DIR.resolve(name + ".otf");
            }
            if (!Files.exists(fontPath)) return null;
            return new AWTFontRenderer(name, fontPath, 12f);
        });
    }

    public static void closeAll() {
        loadedFonts.values().forEach(r -> { if (r != null) r.close(); });
        loadedFonts.clear();
    }

    public static void openFontsFolder() {
        try {
            Files.createDirectories(FONTS_DIR);
            Runtime.getRuntime().exec(new String[]{"explorer", FONTS_DIR.toAbsolutePath().toString()});
        } catch (Exception ignored) {}
    }
}
