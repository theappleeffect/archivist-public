package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrollable list with selectable items. Each item is a string rendered
 * as a row. Supports selection callback, color-per-row, smooth scrolling,
 * clickable URLs, and tooltip offers for truncated text.
 */
public class ScrollableList extends Widget {

    public static class ListItem {
        public final String text;
        public final int color;
        public final Object data;
        public ListItem(String text) {
            this(text, 0, null);
        }

        public ListItem(String text, int color) {
            this(text, color, null);
        }

        public ListItem(String text, int color, Object data) {
            this.text = text;
            this.color = color;
            this.data = data;
        }
    }

    private static final int ITEM_HEIGHT = 12;
    private static final int ITEM_PADDING = 2;
    private static final double SCROLL_SPEED = 0.3;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-.~:/?#\\[\\]@!$&'()*+,;=%]+)");

    private final List<ListItem> items = new ArrayList<>();
    private int selectedIndex = -1;
    private double scrollOffset = 0;
    private double targetScrollOffset = 0;
    private double maxScroll = 0;
    private Consumer<Integer> onSelect;
    private RightClickHandler onRightClick;
    private boolean autoScroll = false;
    private boolean draggingScrollbar = false;
    private double scrollbarDragStartY = 0;
    private double scrollOffsetDragStart = 0;

    public ScrollableList(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        int totalH = items.size() * ITEM_HEIGHT;
        maxScroll = Math.max(0, totalH - height);

        if (autoScroll && maxScroll > targetScrollOffset) {
            targetScrollOffset = maxScroll;
        }

        targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxScroll));

        if (!draggingScrollbar) {
            scrollOffset += (targetScrollOffset - scrollOffset) * SCROLL_SPEED;
            if (Math.abs(targetScrollOffset - scrollOffset) < 0.5) {
                scrollOffset = targetScrollOffset;
            }
        }

        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        boolean hasScrollbar = maxScroll > 0;
        int itemWidth = hasScrollbar ? width - 6 : width;

        RenderUtils.enableScissor(g, x, y, width, height);

        int startIdx = (int) (scrollOffset / ITEM_HEIGHT);
        int endIdx = Math.min(items.size(), startIdx + (height / ITEM_HEIGHT) + 2);

        for (int i = startIdx; i < endIdx; i++) {
            int iy = y + (i * ITEM_HEIGHT) - (int) scrollOffset;
            if (iy + ITEM_HEIGHT < y || iy > y + height) continue;

            ListItem item = items.get(i);
            boolean itemHover = mouseX >= x && mouseX < x + itemWidth
                    && mouseY >= iy && mouseY < iy + ITEM_HEIGHT;

            if (i == selectedIndex) {
                RenderUtils.drawRect(g, x, iy, itemWidth, ITEM_HEIGHT, cs.listSelected());
            } else if (itemHover) {
                RenderUtils.drawRect(g, x, iy, itemWidth, ITEM_HEIGHT, cs.listHover());
            }

            int textColor;
            if (i == selectedIndex) {
                textColor = cs.listTextSelected();
            } else if (item.color != 0) {
                textColor = item.color;
            } else {
                textColor = cs.listText();
            }

            int textMaxW = itemWidth - ITEM_PADDING * 2;
            int textY = iy + (ITEM_HEIGHT - RenderUtils.scaledFontHeight()) / 2;

            Matcher urlMatcher = URL_PATTERN.matcher(item.text);
            if (urlMatcher.find()) {
                renderWithUrls(g, item.text, x + ITEM_PADDING, textY, textColor, textMaxW, mouseX, mouseY);
            } else {
                String display = RenderUtils.trimToWidth(item.text, textMaxW);
                RenderUtils.drawText(g, display, x + ITEM_PADDING, textY, textColor);

                if (!display.equals(item.text) && itemHover) {
                    TooltipManager.offer(item.text, mouseX, mouseY);
                }
            }
        }

        RenderUtils.disableScissor(g);

        if (hasScrollbar) {
            int scrollTrackX = x + width - 5;
            RenderUtils.drawRect(g, scrollTrackX, y, 5, height, cs.scrollbarTrack());

            float ratio = (float) height / totalH;
            int thumbH = Math.max(10, (int) (height * ratio));
            int thumbY = y + (int) ((height - thumbH) * (scrollOffset / maxScroll));

            boolean sbHover = mouseX >= scrollTrackX && mouseX < scrollTrackX + 5
                    && mouseY >= thumbY && mouseY < thumbY + thumbH;
            RenderUtils.drawRect(g, scrollTrackX, thumbY, 5, thumbH,
                    (draggingScrollbar || sbHover) ? cs.scrollbarHover() : cs.scrollbarThumb());
        }
    }

    private void renderWithUrls(GuiGraphics g, String text, int textX, int textY,
                                int defaultColor, int maxWidth, int mouseX, int mouseY) {
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;
        int currentX = textX;
        int accentColor = ColorScheme.get().accent();

        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                int w = RenderUtils.scaledTextWidth(before);
                if (currentX + w - textX > maxWidth) break;
                RenderUtils.drawText(g, before, currentX, textY, defaultColor);
                currentX += w;
            }

            String url = matcher.group(1);
            int urlW = RenderUtils.scaledTextWidth(url);
            if (currentX + urlW - textX > maxWidth) {
                url = RenderUtils.trimToWidth(url, maxWidth - (currentX - textX));
                urlW = RenderUtils.scaledTextWidth(url);
            }

            boolean urlHover = mouseX >= currentX && mouseX < currentX + urlW
                    && mouseY >= textY && mouseY < textY + RenderUtils.scaledFontHeight();
            int urlColor = urlHover ? ColorScheme.get().textPrimary() : accentColor;
            RenderUtils.drawText(g, url, currentX, textY, urlColor);

            int underlineY = textY + RenderUtils.scaledFontHeight();
            g.fill(currentX, underlineY, currentX + urlW, underlineY + 1, urlColor);

            currentX += urlW;
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            if (currentX - textX < maxWidth) {
                String trimmed = RenderUtils.trimToWidth(remaining, maxWidth - (currentX - textX));
                RenderUtils.drawText(g, trimmed, currentX, textY, defaultColor);
            }
        }
    }

    @FunctionalInterface
    public interface RightClickHandler {
        void onRightClick(ListItem item, int index, int mouseX, int mouseY);
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !containsPoint(mouseX, mouseY)) return false;

        if (button == 1 && onRightClick != null) {
            int relY = (int) mouseY - y + (int) scrollOffset;
            int idx = relY / ITEM_HEIGHT;
            if (idx >= 0 && idx < items.size()) {
                onRightClick.onRightClick(items.get(idx), idx, (int) mouseX, (int) mouseY);
                return true;
            }
        }

        if (button == 0) {
            int scrollTrackX = x + width - 5;
            if (maxScroll > 0 && mouseX >= scrollTrackX && mouseX < scrollTrackX + 5) {
                draggingScrollbar = true;
                scrollbarDragStartY = mouseY;
                scrollOffsetDragStart = scrollOffset;
                targetScrollOffset = scrollOffset;
                return true;
            }

            int relY = (int) mouseY - y + (int) scrollOffset;
            int idx = relY / ITEM_HEIGHT;
            if (idx >= 0 && idx < items.size()) {
                String clickedUrl = getUrlAtPosition(mouseX, mouseY, idx);
                if (clickedUrl != null) {
                    openUrlSafely(clickedUrl);
                    return true;
                }

                selectedIndex = idx;
                if (onSelect != null) onSelect.accept(idx);
                return true;
            }
        }
        return true;
    }

    private String getUrlAtPosition(double mouseX, double mouseY, int itemIndex) {
        if (itemIndex < 0 || itemIndex >= items.size()) return null;
        ListItem item = items.get(itemIndex);
        Matcher matcher = URL_PATTERN.matcher(item.text);

        int textX = x + ITEM_PADDING;
        int currentX = textX;
        int lastEnd = 0;
        int textMaxW = (maxScroll > 0 ? width - 6 : width) - ITEM_PADDING * 2;

        while (matcher.find()) {
            String before = item.text.substring(lastEnd, matcher.start());
            currentX += RenderUtils.scaledTextWidth(before);

            String url = matcher.group(1);
            int urlW = RenderUtils.scaledTextWidth(url);
            if (currentX + urlW - textX > textMaxW) break;

            if (mouseX >= currentX && mouseX < currentX + urlW) {
                return url;
            }

            currentX += urlW;
            lastEnd = matcher.end();
        }
        return null;
    }

    private void openUrlSafely(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return;
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return;
            com.archivist.api.ApiGuiHelper.openUrl(url);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingScrollbar && button == 0) {
            int totalH = items.size() * ITEM_HEIGHT;
            float ratio = (float) height / totalH;
            int thumbH = Math.max(10, (int) (height * ratio));
            int maxThumbTravel = height - thumbH;
            if (maxThumbTravel <= 0) return true;

            double mouseDelta = mouseY - scrollbarDragStartY;
            double newOffset = scrollOffsetDragStart + (mouseDelta / maxThumbTravel) * maxScroll;
            newOffset = Math.max(0, Math.min(newOffset, maxScroll));

            scrollOffset = newOffset;
            targetScrollOffset = newOffset;
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            autoScroll = targetScrollOffset >= maxScroll - 1;
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!visible || !containsPoint(mouseX, mouseY)) return false;
        if (maxScroll > 0) {
            targetScrollOffset -= vAmount * ITEM_HEIGHT * 3;
            targetScrollOffset = Math.max(0, Math.min(targetScrollOffset, maxScroll));
            autoScroll = targetScrollOffset >= maxScroll - 1;
            return true;
        }
        return false;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public void addItem(ListItem item) {
        items.add(item);
    }

    public void addItem(String text) {
        items.add(new ListItem(text));
    }

    public void addItem(String text, int color) {
        items.add(new ListItem(text, color));
    }

    public void setItems(List<ListItem> newItems) {
        items.clear();
        items.addAll(newItems);
        if (selectedIndex >= items.size()) selectedIndex = -1;
    }

    public void clearItems() {
        items.clear();
        selectedIndex = -1;
        scrollOffset = 0;
        targetScrollOffset = 0;
    }

    public List<ListItem> getItems() { return items; }
    public int getItemCount() { return items.size(); }

    public int getSelectedIndex() { return selectedIndex; }
    public void setSelectedIndex(int idx) { this.selectedIndex = idx; }

    public ListItem getSelectedItem() {
        return selectedIndex >= 0 && selectedIndex < items.size() ? items.get(selectedIndex) : null;
    }

    public void setOnSelect(Consumer<Integer> onSelect) { this.onSelect = onSelect; }
    public void setOnRightClick(RightClickHandler handler) { this.onRightClick = handler; }

    public void setAutoScroll(boolean autoScroll) { this.autoScroll = autoScroll; }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible || !containsPoint(px, py)) return null;
        int relY = (int) py - y + (int) scrollOffset;
        int idx = relY / ITEM_HEIGHT;
        if (idx >= 0 && idx < items.size()) {
            return items.get(idx).text;
        }
        return null;
    }

    public void scrollToBottom() {
        scrollOffset = maxScroll;
        targetScrollOffset = maxScroll;
    }

    public void scrollToItem(int index) {
        if (index < 0 || index >= items.size()) return;
        int itemTop = index * ITEM_HEIGHT;
        int itemBottom = itemTop + ITEM_HEIGHT;
        if (itemTop < scrollOffset) {
            targetScrollOffset = itemTop;
        } else if (itemBottom > scrollOffset + height) {
            targetScrollOffset = itemBottom - height;
        }
        autoScroll = false;
    }
}
