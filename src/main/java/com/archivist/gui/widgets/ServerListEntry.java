package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A single row in the Server List, displaying a summary of one logged server.
 */
public class ServerListEntry extends Widget {

    public static final int ENTRY_HEIGHT = 32;
    private static final int PADDING = 4;

    private final String serverAddress;
    private final String version;
    private final String brand;
    private final int pluginCount;
    private final int sessionCount;
    private final String lastVisited;
    private boolean selected = false;

    public ServerListEntry(int x, int y, int width,
                           String serverAddress, String version, String brand,
                           int pluginCount, int sessionCount, String lastVisited) {
        super(x, y, width, ENTRY_HEIGHT);
        this.serverAddress = serverAddress;
        this.version = version;
        this.brand = brand;
        this.pluginCount = pluginCount;
        this.sessionCount = sessionCount;
        this.lastVisited = lastVisited;
    }

    public String getServerAddress() { return serverAddress; }
    public String getVersion() { return version; }
    public String getBrand() { return brand; }
    public int getPluginCount() { return pluginCount; }
    public int getSessionCount() { return sessionCount; }
    public String getLastVisited() { return lastVisited; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        int bgColor;
        if (selected) bgColor = cs.listSelected();
        else if (hovered) bgColor = cs.listHover();
        else bgColor = ColorScheme.withAlpha(cs.windowBackground(), 80);

        RenderUtils.drawRect(g, x, y, width, height, bgColor);

        RenderUtils.drawHLine(g, x, y + height - 1, width, cs.separator());

        int textColor = selected ? cs.listTextSelected() : cs.textPrimary();
        int dimColor = selected ? cs.listTextSelected() : cs.textSecondary();
        int accentColor = cs.accent();

        int textX = x + PADDING;
        int line1Y = y + PADDING;
        int line2Y = y + PADDING + RenderUtils.scaledFontHeight() + 3;

        RenderUtils.drawText(g, serverAddress, textX, line1Y, textColor);

        String pluginText = pluginCount + (pluginCount == 1 ? " plugin" : " plugins");
        int pluginTextW = RenderUtils.scaledTextWidth(pluginText);
        RenderUtils.drawText(g, pluginText, x + width - PADDING - pluginTextW, line1Y, accentColor);

        String brandVersion = (brand != null && !brand.isEmpty() && !brand.equals("unknown"))
                ? brand + " " + version : version;
        String line2 = brandVersion + "  |  Last: " + lastVisited + "  |  "
                + sessionCount + (sessionCount == 1 ? " session" : " sessions");

        String trimmed = RenderUtils.trimToWidth(line2, width - PADDING * 2);
        RenderUtils.drawText(g, trimmed, textX, line2Y, dimColor);

        if (!trimmed.equals(line2) && hovered) {
            TooltipManager.offer(line2, mouseX, mouseY);
        }
    }
}
