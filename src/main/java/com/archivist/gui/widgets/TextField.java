package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single-line text input field with cursor, selection, and placeholder.
 * Supports optional password masking.
 */
public class TextField extends Widget {

    private String text = "";
    private String placeholder;
    private boolean masked;
    private Consumer<String> onChange;
    private int cursorPos = 0;
    private int selectionStart = -1; // -1 = no selection
    private int scrollOffset = 0;
    private long cursorBlinkTime = 0;

    private Function<String, List<String>> autoCompleteProvider;
    private Consumer<List<String>> onShowSuggestions;

    private boolean error = false;
    private String errorTooltip = null;

    private int baseWidth;

    public TextField(int x, int y, int width, int height, String placeholder) {
        super(x, y, width, height);
        this.placeholder = placeholder;
        this.masked = false;
        this.baseWidth = width;
        this.fixedWidth = width;
    }

    public TextField(int x, int y, int width, String placeholder) {
        this(x, y, width, 14, placeholder);
    }

    public TextField(int x, int y, int width, String placeholder, boolean masked) {
        this(x, y, width, 14, placeholder);
        this.masked = masked;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        RenderUtils.drawRect(g, x, y, width, height, cs.textFieldBg());

        int borderColor = error ? cs.eventError() : (focused ? cs.textFieldFocused() : cs.textFieldBorder());
        RenderUtils.drawBorder(g, x, y, width, height, borderColor);

        int pad = 3;
        int textAreaW = width - pad * 2;
        RenderUtils.enableScissor(g, x + pad, y, textAreaW, height);

        String displayText = masked ? "*".repeat(text.length()) : text;

        if (text.isEmpty() && !focused && placeholder != null) {
            RenderUtils.drawText(g, placeholder, x + pad, y + (height - RenderUtils.scaledFontHeight()) / 2, cs.placeholder());
        } else {
            adjustScroll(displayText, textAreaW);

            String scrolledText = displayText.substring(Math.min(scrollOffset, displayText.length()));
            int textY = y + (height - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, scrolledText, x + pad, textY, cs.textFieldText());

            // Draw selection highlight
            if (focused && hasSelection()) {
                int selMin = Math.min(selectionStart, cursorPos);
                int selMax = Math.max(selectionStart, cursorPos);
                int visStart = Math.max(selMin, scrollOffset) - scrollOffset;
                int visEnd = Math.max(selMax, scrollOffset) - scrollOffset;
                if (visEnd > 0 && visStart < scrolledText.length()) {
                    visStart = Math.max(0, visStart);
                    visEnd = Math.min(scrolledText.length(), visEnd);
                    int hlX1 = x + pad + RenderUtils.scaledTextWidth(scrolledText.substring(0, visStart));
                    int hlX2 = x + pad + RenderUtils.scaledTextWidth(scrolledText.substring(0, visEnd));
                    g.fill(hlX1, y + 2, hlX2, y + height - 2, ColorScheme.withAlpha(ColorScheme.get().accent(), 0x80));
                }
            }

            if (focused && !hasSelection() && (System.currentTimeMillis() - cursorBlinkTime) % 1000 < 500) {
                String beforeCursor = displayText.substring(Math.min(scrollOffset, displayText.length()),
                        Math.min(cursorPos, displayText.length()));
                int cursorX = x + pad + RenderUtils.scaledTextWidth(beforeCursor);
                int cursorY1 = y + 2;
                int cursorY2 = y + height - 2;
                g.fill(cursorX, cursorY1, cursorX + 1, cursorY2, cs.cursor());
            }
        }

        RenderUtils.disableScissor(g);
    }

    private void adjustScroll(String displayText, int textAreaW) {
        scrollOffset = Math.max(0, scrollOffset);
        if (cursorPos < scrollOffset) {
            scrollOffset = cursorPos;
        }
        String visible = displayText.substring(Math.min(scrollOffset, displayText.length()),
                Math.min(cursorPos, displayText.length()));
        while (RenderUtils.scaledTextWidth(visible) > textAreaW - 4 && scrollOffset < cursorPos) {
            scrollOffset++;
            visible = displayText.substring(Math.min(scrollOffset, displayText.length()),
                    Math.min(cursorPos, displayText.length()));
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        if (containsPoint(mouseX, mouseY)) {
            focused = true;
            selectionStart = -1;
            cursorBlinkTime = System.currentTimeMillis();
            int pad = 3;
            String displayText = masked ? "*".repeat(text.length()) : text;
            String scrolledText = displayText.substring(Math.min(scrollOffset, displayText.length()));
            int relX = (int) mouseX - x - pad;
            int pos = scrollOffset;
            for (int i = 0; i < scrolledText.length(); i++) {
                int charW = RenderUtils.scaledTextWidth(scrolledText.substring(0, i + 1));
                if (charW > relX) break;
                pos = scrollOffset + i + 1;
            }
            cursorPos = Math.min(pos, text.length());
            return true;
        } else {
            focused = false;
            return false;
        }
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!focused || !visible || button != 0) return false;
        if (!containsPoint(mouseX, mouseY) && !containsPoint(mouseX - dx, mouseY - dy)) return false;
        if (selectionStart < 0) selectionStart = cursorPos;
        int pad = 3;
        String displayText = masked ? "*".repeat(text.length()) : text;
        String scrolledText = displayText.substring(Math.min(scrollOffset, displayText.length()));
        int relX = (int) mouseX - x - pad;
        int pos = scrollOffset;
        for (int i = 0; i < scrolledText.length(); i++) {
            int charW = RenderUtils.scaledTextWidth(scrolledText.substring(0, i + 1));
            if (charW > relX) break;
            pos = scrollOffset + i + 1;
        }
        cursorPos = Math.min(pos, text.length());
        return true;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        cursorBlinkTime = System.currentTimeMillis();

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (!text.isEmpty() && cursorPos > 0) {
                    if (ctrl) {
                        int end = cursorPos;
                        while (cursorPos > 0 && text.charAt(cursorPos - 1) == ' ') cursorPos--;
                        while (cursorPos > 0 && text.charAt(cursorPos - 1) != ' ') cursorPos--;
                        text = text.substring(0, cursorPos) + text.substring(end);
                    } else {
                        text = text.substring(0, cursorPos - 1) + text.substring(cursorPos);
                        cursorPos--;
                    }
                    notifyChange();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (cursorPos < text.length()) {
                    text = text.substring(0, cursorPos) + text.substring(cursorPos + 1);
                    notifyChange();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                if (shift && selectionStart < 0) selectionStart = cursorPos;
                if (!shift) selectionStart = -1;
                if (cursorPos > 0) {
                    if (ctrl) {
                        while (cursorPos > 0 && text.charAt(cursorPos - 1) == ' ') cursorPos--;
                        while (cursorPos > 0 && text.charAt(cursorPos - 1) != ' ') cursorPos--;
                    } else {
                        cursorPos--;
                    }
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                if (shift && selectionStart < 0) selectionStart = cursorPos;
                if (!shift) selectionStart = -1;
                if (cursorPos < text.length()) {
                    if (ctrl) {
                        while (cursorPos < text.length() && text.charAt(cursorPos) != ' ') cursorPos++;
                        while (cursorPos < text.length() && text.charAt(cursorPos) == ' ') cursorPos++;
                    } else {
                        cursorPos++;
                    }
                }
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                if (shift && selectionStart < 0) selectionStart = cursorPos;
                if (!shift) selectionStart = -1;
                cursorPos = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                if (shift && selectionStart < 0) selectionStart = cursorPos;
                if (!shift) selectionStart = -1;
                cursorPos = text.length();
                return true;
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) {
                    selectionStart = 0;
                    cursorPos = text.length();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    if (hasSelection()) deleteSelection();
                    String clipboard = net.minecraft.client.Minecraft.getInstance()
                            .keyboardHandler.getClipboard();
                    if (clipboard != null && !clipboard.isEmpty()) {
                        clipboard = clipboard.replaceAll("[\\r\\n]", " ");
                        text = text.substring(0, cursorPos) + clipboard + text.substring(cursorPos);
                        cursorPos += clipboard.length();
                        notifyChange();
                    }
                    return true;
                }
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl && !text.isEmpty()) {
                    String toCopy = hasSelection() ? getSelectedText() : text;
                    net.minecraft.client.Minecraft.getInstance()
                            .keyboardHandler.setClipboard(toCopy);
                    return true;
                }
            }
            case GLFW.GLFW_KEY_X -> {
                if (ctrl && !text.isEmpty()) {
                    String toCut = hasSelection() ? getSelectedText() : text;
                    net.minecraft.client.Minecraft.getInstance()
                            .keyboardHandler.setClipboard(toCut);
                    if (hasSelection()) {
                        deleteSelection();
                    } else {
                        text = "";
                        cursorPos = 0;
                        selectionStart = -1;
                        notifyChange();
                    }
                    return true;
                }
            }
            case GLFW.GLFW_KEY_TAB -> {
                if (autoCompleteProvider != null) {
                    String input = getText();
                    if (input.isEmpty()) return false;
                    List<String> matches = autoCompleteProvider.apply(input.toLowerCase());
                    if (matches == null || matches.isEmpty()) return true;
                    if (matches.size() == 1) {
                        setText(matches.get(0) + " ");
                        cursorPos = text.length();
                    } else {
                        if (onShowSuggestions != null) {
                            onShowSuggestions.accept(matches);
                        }
                        String prefix = findCommonPrefix(matches);
                        if (prefix.length() > input.length()) {
                            setText(prefix);
                            cursorPos = text.length();
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private String findCommonPrefix(List<String> strings) {
        if (strings.isEmpty()) return "";
        String first = strings.get(0);
        int prefixLen = first.length();
        for (int i = 1; i < strings.size(); i++) {
            String s = strings.get(i);
            prefixLen = Math.min(prefixLen, s.length());
            for (int j = 0; j < prefixLen; j++) {
                if (first.charAt(j) != s.charAt(j)) {
                    prefixLen = j;
                    break;
                }
            }
        }
        return first.substring(0, prefixLen);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (!focused) return false;
        if (chr < 32) return false;
        cursorBlinkTime = System.currentTimeMillis();
        if (hasSelection()) deleteSelection();
        text = text.substring(0, cursorPos) + chr + text.substring(cursorPos);
        cursorPos++;
        notifyChange();
        return true;
    }

    private void notifyChange() {
        // Grow width if text exceeds current size, but never shrink below base
        int textW = RenderUtils.scaledTextWidth(masked ? "*".repeat(text.length()) : text) + 12; // 6px padding each side
        int newWidth = Math.max(baseWidth, textW);
        if (newWidth != (int) fixedWidth) {
            fixedWidth = newWidth;
            width = newWidth;
        }
        if (onChange != null) onChange.accept(text);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public String getText() { return text; }

    public void setText(String text) {
        this.text = text != null ? text : "";
        this.cursorPos = Math.min(cursorPos, this.text.length());
    }

    public void setCursorPos(int pos) {
        this.cursorPos = Math.max(0, Math.min(pos, text.length()));
    }

    public int getCursorPos() { return cursorPos; }

    public void setOnChange(Consumer<String> onChange) {
        this.onChange = onChange;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public void setMasked(boolean masked) {
        this.masked = masked;
    }

    public void setError(boolean e) { this.error = e; }
    public void setErrorTooltip(String tip) { this.errorTooltip = tip; }

    public void clear() {
        text = "";
        cursorPos = 0;
        scrollOffset = 0;
        notifyChange();
    }

    public void setAutoCompleteProvider(Function<String, List<String>> provider) {
        this.autoCompleteProvider = provider;
    }

    public void setOnShowSuggestions(Consumer<List<String>> callback) {
        this.onShowSuggestions = callback;
    }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible || text == null || text.isEmpty()) return null;
        return containsPoint(px, py) ? text : null;
    }

    private boolean hasSelection() {
        return selectionStart >= 0 && selectionStart != cursorPos;
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int from = Math.min(selectionStart, cursorPos);
        int to = Math.max(selectionStart, cursorPos);
        return text.substring(from, to);
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int from = Math.min(selectionStart, cursorPos);
        int to = Math.max(selectionStart, cursorPos);
        text = text.substring(0, from) + text.substring(to);
        cursorPos = from;
        selectionStart = -1;
        notifyChange();
    }
}
