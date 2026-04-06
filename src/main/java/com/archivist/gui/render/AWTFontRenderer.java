package com.archivist.gui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.9
import net.minecraft.client.renderer.RenderPipelines;
//? if >=1.21.11
import net.minecraft.resources.Identifier;
//? if <1.21.11
/*import net.minecraft.resources.ResourceLocation;*/
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//? if <1.21.9
/*import com.mojang.blaze3d.systems.RenderSystem;*/

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class AWTFontRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final int ATLAS_SIZE = 1024;
    private static final int PADDING = 4;
    private static final int OVERSAMPLE = 1;

    private final String name;
    private final float renderScale;
    private final Map<Character, CharData> charMap = new HashMap<>();
    //? if >=1.21.11
    private Identifier atlasId;
    //? if <1.21.11
    /*private ResourceLocation atlasId;*/
    private int fontHeight;
    private int scaledHeight;
    private boolean ready = false;

    private record CharData(int atlasX, int atlasY, int width, int height, int scaledWidth) {}

    public AWTFontRenderer(String name, Path ttfPath, float size) {
        this.name = name;
        this.renderScale = 1.0f / OVERSAMPLE;
        try {
            float oversampledSize = size * OVERSAMPLE;
            java.awt.Font awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                    new FileInputStream(ttfPath.toFile())).deriveFont(oversampledSize);
            buildAtlas(awtFont);
            ready = true;
            LOGGER.info("Loaded custom font: {} ({}px, {}x oversample)", name, (int) size, OVERSAMPLE);
        } catch (Exception e) {
            LOGGER.error("Failed to load font: " + name, e);
        }
    }

    private void buildAtlas(java.awt.Font awtFont) {
        BufferedImage img = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setFont(awtFont);
        FontMetrics metrics = g2d.getFontMetrics();
        fontHeight = metrics.getHeight();
        scaledHeight = Math.round(fontHeight * renderScale);
        int ascent = metrics.getAscent();

        int cursorX = PADDING;
        int cursorY = PADDING;
        int rowHeight = fontHeight + PADDING;

        for (int c = 32; c < 127; c++) {
            char ch = (char) c;
            int charWidth = metrics.charWidth(ch);
            if (charWidth <= 0) continue;

            if (cursorX + charWidth + PADDING > ATLAS_SIZE) {
                cursorX = PADDING;
                cursorY += rowHeight;
                if (cursorY + rowHeight > ATLAS_SIZE) break;
            }

            g2d.setColor(Color.WHITE);
            g2d.drawString(String.valueOf(ch), cursorX, cursorY + ascent);

            int scaledW = Math.round(charWidth * renderScale);
            charMap.put(ch, new CharData(cursorX, cursorY, charWidth, fontHeight, scaledW));
            cursorX += charWidth + PADDING;
        }
        g2d.dispose();

        var nativeImage = new com.mojang.blaze3d.platform.NativeImage(ATLAS_SIZE, ATLAS_SIZE, false);
        for (int y = 0; y < ATLAS_SIZE; y++) {
            for (int x = 0; x < ATLAS_SIZE; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                //? if >=1.21.3
                nativeImage.setPixel(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                //? if <1.21.3
                /*nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);*/
            }
        }

        String idPath = "font/" + name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        //? if >=1.21.11 {
        var dynamicTexture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "archivist_font", nativeImage);
        atlasId = Identifier.fromNamespaceAndPath("archivist", idPath);
        //?} else if (>=1.21.5) {
        /*var dynamicTexture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "archivist_font", nativeImage);
        atlasId = ResourceLocation.fromNamespaceAndPath("archivist", idPath);*/
        //?} else {
        /*var dynamicTexture = new net.minecraft.client.renderer.texture.DynamicTexture(nativeImage);
        atlasId = ResourceLocation.fromNamespaceAndPath("archivist", idPath);*/
        //?}
        Minecraft.getInstance().getTextureManager().register(atlasId, dynamicTexture);
    }

    public int drawString(GuiGraphics graphics, String text, int x, int y, int color, boolean shadow) {
        if (!ready || text == null || text.isEmpty()) return x;
        if (shadow) {
            drawChars(graphics, text, x + 1, y + 1, darken(color));
        }
        drawChars(graphics, text, x, y, color);
        return x + width(text);
    }

    private void drawChars(GuiGraphics graphics, String text, int x, int y, int color) {
        var mc = Minecraft.getInstance();
        int alpha = (color >> 24) & 0xFF;
        if (alpha == 0) color = color | 0xFF000000;

        //? if <1.21.9 {
        /*float fa = ((color >> 24) & 0xFF) / 255.0f;
        float fr = ((color >> 16) & 0xFF) / 255.0f;
        float fg = ((color >> 8) & 0xFF) / 255.0f;
        float fb = (color & 0xFF) / 255.0f;
        RenderSystem.setShaderColor(fr, fg, fb, fa);*/
        //?}

        int drawX = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            CharData cd = charMap.get(ch);
            if (cd == null) {
                //? if <1.21.9
                /*RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);*/
                graphics.drawString(mc.font, String.valueOf(ch), drawX, y, color, false);
                //? if <1.21.9
                /*RenderSystem.setShaderColor(fr, fg, fb, fa);*/
                drawX += mc.font.width(String.valueOf(ch));
                continue;
            }
            //? if >=1.21.9
            graphics.blit(RenderPipelines.GUI_TEXTURED, atlasId, drawX, y, (float) cd.atlasX, (float) cd.atlasY, cd.scaledWidth, scaledHeight, ATLAS_SIZE, ATLAS_SIZE, color);
            //? if <1.21.9 && >=1.21.3
            /*graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, atlasId, drawX, y, (float) cd.atlasX, (float) cd.atlasY, cd.scaledWidth, scaledHeight, ATLAS_SIZE, ATLAS_SIZE);*/
            //? if <1.21.3
            /*graphics.blit(atlasId, drawX, y, (float) cd.atlasX, (float) cd.atlasY, cd.scaledWidth, scaledHeight, ATLAS_SIZE, ATLAS_SIZE);*/
            drawX += cd.scaledWidth;
        }
        //? if <1.21.9
        /*RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);*/
    }

    private static int darken(int color) {
        int a = (color >> 24) & 0xFF;
        int r = ((color >> 16) & 0xFF) / 4;
        int g = ((color >> 8) & 0xFF) / 4;
        int b = (color & 0xFF) / 4;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public int width(String text) {
        if (!ready || text == null) return 0;
        var mc = Minecraft.getInstance();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            CharData cd = charMap.get(ch);
            if (cd != null) {
                w += cd.scaledWidth;
            } else {
                w += mc.font.width(String.valueOf(ch));
            }
        }
        return w;
    }

    public int lineHeight() {
        return ready ? scaledHeight : 9;
    }

    public boolean isReady() {
        return ready;
    }

    public String getName() {
        return name;
    }

    public void close() {
        atlasId = null;
    }
}
