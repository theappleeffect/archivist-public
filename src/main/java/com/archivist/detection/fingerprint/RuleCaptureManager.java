package com.archivist.detection.fingerprint;

import com.archivist.detection.fingerprint.model.GuiRule;
import com.archivist.gui.widgets.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the in-game GUI rule capture workflow.
 * User presses F6 to enter capture mode: a styled bar with a text input appears
 * above the container GUI. User clicks slots to mark them and types the plugin
 * name simultaneously. Save with Enter or the Save button; cancel with F6/Escape.
 */
public class RuleCaptureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    public enum State { INACTIVE, SELECTING }

    private State state = State.INACTIVE;
    private final Set<Integer> selectedSlots = new LinkedHashSet<>();
    private GuiCapture lastCapture;
    private String inputText = "";
    private TextField nameField;

    public State getState() { return state; }

    /** Get the active text field (created when capture starts, null when inactive). */
    public TextField getNameField() { return nameField; }

    public void startCapture() {
        state = State.SELECTING;
        selectedSlots.clear();
        inputText = "";
        nameField = null; // will be lazily created by the render mixin
    }

    public void cancel() {
        state = State.INACTIVE;
        selectedSlots.clear();
        inputText = "";
        nameField = null;
    }

    /** Called by the render mixin to set the lazily-created text field. */
    public void setNameField(TextField field) {
        this.nameField = field;
    }

    public void toggleSlot(int slot) {
        if (state != State.SELECTING) return;
        if (selectedSlots.contains(slot)) {
            selectedSlots.remove(slot);
        } else {
            selectedSlots.add(slot);
        }
    }

    public Set<Integer> getSelectedSlots() {
        return Collections.unmodifiableSet(selectedSlots);
    }

    public boolean isSlotSelected(int slot) {
        return selectedSlots.contains(slot);
    }

    public void setLastCapture(GuiCapture capture) {
        // If selecting and the GUI changed (different syncId), clear slot selections
        // since they refer to the old GUI's layout
        if (state == State.SELECTING && lastCapture != null && capture != null
                && lastCapture.syncId() != capture.syncId()) {
            selectedSlots.clear();
        }
        this.lastCapture = capture;
    }

    public GuiCapture getLastCapture() {
        return lastCapture;
    }

    public String getInputText() {
        return inputText;
    }

    public void setInputText(String text) {
        this.inputText = text;
    }

    /**
     * Build a GuiRule from the selected markers and save it.
     * Plugin name is taken from inputText, auto-lowercased, leading slashes stripped.
     * If a rule with the same pluginId already exists, the new pattern is appended.
     * Always resets state to INACTIVE regardless of success or failure.
     */
    public GuiRule buildRule(GuiRuleDatabase database) {
        try {
            // Clean the input: strip leading slashes, trim, lowercase
            String cleanName = inputText.trim();
            while (cleanName.startsWith("/")) cleanName = cleanName.substring(1);
            cleanName = cleanName.toLowerCase(Locale.ROOT).trim();

            if (lastCapture == null || selectedSlots.isEmpty() || cleanName.isBlank()) {
                return null;
            }

            String pluginId = cleanName.replaceAll("[^a-z0-9_-]", "_");

            // Build markers from selected slots
            List<GuiRule.SlotMarker> markers = new ArrayList<>();
            for (CapturedItem item : lastCapture.items()) {
                if (selectedSlots.contains(item.slot())) {
                    markers.add(new GuiRule.SlotMarker(
                            item.slot(),
                            item.materialId(),
                            item.displayName(),
                            item.displayNameJson(),
                            item.lore(),
                            item.loreJson(),
                            item.count(),
                            item.customModelData(),
                            item.hasEnchantGlint()
                    ));
                }
            }

            if (markers.isEmpty()) return null;

            GuiRule.GuiPattern pattern = new GuiRule.GuiPattern(
                    lastCapture.title(),
                    lastCapture.title(),
                    0.9,
                    markers
            );

            // Check if rule already exists — append pattern
            GuiRule existing = null;
            for (GuiRule rule : database.getAll()) {
                if (rule.pluginId().equals(pluginId)) {
                    existing = rule;
                    break;
                }
            }

            GuiRule rule;
            if (existing != null) {
                List<GuiRule.GuiPattern> patterns = new ArrayList<>(existing.patterns());
                patterns.add(pattern);
                rule = new GuiRule(existing.pluginId(), existing.pluginName(), patterns);
            } else {
                rule = new GuiRule(pluginId, cleanName, List.of(pattern));
            }

            database.save(rule);
            LOGGER.info("[Archivist] Saved GUI rule: {} ({} patterns)", rule.pluginName(), rule.patterns().size());
            return rule;
        } finally {
            state = State.INACTIVE;
            selectedSlots.clear();
            inputText = "";
            nameField = null;
        }
    }
}
