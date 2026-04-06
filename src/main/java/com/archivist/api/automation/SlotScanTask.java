package com.archivist.api.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans the player's hotbar for lobby selector items (compass, nether star, etc.)
 * and right-clicks them to open server selector menus.
 * LOBBY ONLY — runs before NPC interaction as the primary way to open menus.
 *
 * <p>If a GUI opens, the subsequent {@link ContainerWalkTask} will handle navigation.</p>
 * <p>If ContainerWalkTask fails (no game-mode match), this task can be resumed to try
 * the next candidate via {@link #resumeFromFailedGui()}.</p>
 */
public class SlotScanTask implements TickTask {

    private enum Phase { WAITING_INVENTORY, SCANNING, SWITCHING, USING, WAITING_GUI, DONE }

    // Item types commonly used as lobby selectors
    private static final Set<Item> SELECTOR_ITEMS = Set.of(
            Items.COMPASS, Items.CLOCK, Items.NETHER_STAR, Items.ENDER_EYE,
            Items.ENDER_CHEST, Items.RECOVERY_COMPASS, Items.EMERALD,
            Items.PLAYER_HEAD
    );

    // Keywords in item display names that indicate a selector item
    private static final Set<String> SELECTOR_KEYWORDS = Set.of(
            "menu", "selector", "server", "play", "hub", "lobby", "compass", "navigate"
    );

    private static final int INVENTORY_WAIT_TICKS = 10; // 0.5s fallback
    private static final int SWITCH_TICKS = 2;
    private static final int GUI_WAIT_TICKS = 15; // 0.75s fallback

    private Phase phase = Phase.WAITING_INVENTORY;
    private int ticksInPhase = 0;
    private boolean complete = false;
    private boolean foundItems = false;

    private final List<Integer> candidateSlots = new ArrayList<>();
    private int currentCandidateIndex = 0;
    private int previousSlot = 0;
    private boolean resuming = false;

    @Override
    public void start(Minecraft mc) {
        if (resuming) {
            resuming = false;
            phase = Phase.SWITCHING;
            ticksInPhase = 0;
            return;
        }
        phase = Phase.WAITING_INVENTORY;
        ticksInPhase = 0;
        candidateSlots.clear();
        currentCandidateIndex = 0;
        if (mc.player != null) {
            previousSlot = mc.player.getInventory()./*? if >=1.21.5 {*/getSelectedSlot()/*?} else {*//*selected*//*?}*/;
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (complete) return;
        ticksInPhase++;
        LocalPlayer player = mc.player;
        if (player == null) { complete = true; return; }

        switch (phase) {
            case WAITING_INVENTORY -> {
                // Event-driven: proceed as soon as hotbar has items
                boolean hasItems = false;
                for (int i = 0; i < 9; i++) {
                    if (!player.getInventory().getItem(i).isEmpty()) { hasItems = true; break; }
                }
                if (hasItems || ticksInPhase >= INVENTORY_WAIT_TICKS) {
                    phase = Phase.SCANNING;
                    ticksInPhase = 0;
                }
            }
            case SCANNING -> {
                var inv = player.getInventory();

                // Collect all candidates with their priority
                record Candidate(int slot, int priority) {}
                List<Candidate> candidates = new ArrayList<>();

                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;

                    Item item = stack.getItem();

                    if (item == Items.COMPASS || item == Items.RECOVERY_COMPASS) {
                        candidates.add(new Candidate(i, 0));
                    } else if (item == Items.NETHER_STAR) {
                        candidates.add(new Candidate(i, 1));
                    } else if (item == Items.CLOCK) {
                        candidates.add(new Candidate(i, 2));
                    } else if (item == Items.ARROW || item == Items.SPECTRAL_ARROW || item == Items.TIPPED_ARROW) {
                        candidates.add(new Candidate(i, 3));
                    } else {
                        candidates.add(new Candidate(i, 4));
                    }
                }

                // Sort by priority (compass first, then clock, etc.)
                candidates.sort(Comparator.comparingInt(Candidate::priority));

                candidateSlots.clear();
                for (Candidate c : candidates) {
                    candidateSlots.add(c.slot());
                }

                if (candidateSlots.isEmpty()) {
                    foundItems = false;
                    complete = true; // no selector items found
                } else {
                    foundItems = true;
                    currentCandidateIndex = 0;
                    previousSlot = inv./*? if >=1.21.5 {*/getSelectedSlot()/*?} else {*//*selected*//*?}*/;
                    advanceToNextCandidate(player);
                }
            }
            case SWITCHING -> {
                if (ticksInPhase >= SWITCH_TICKS) {
                    phase = Phase.USING;
                    ticksInPhase = 0;
                }
            }
            case USING -> {
                // Right-click the held item
                if (mc.gameMode != null) {
                    mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                }
                phase = Phase.WAITING_GUI;
                ticksInPhase = 0;
            }
            case WAITING_GUI -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    // GUI opened — ContainerWalkTask will handle it
                    // Don't close it, just mark as done
                    complete = true;
                    return;
                }

                if (ticksInPhase >= GUI_WAIT_TICKS) {
                    // No GUI opened — try next candidate
                    currentCandidateIndex++;
                    if (currentCandidateIndex < candidateSlots.size()) {
                        advanceToNextCandidate(player);
                    } else {
                        // All candidates tried, none opened a GUI
                        // Restore original slot
                        player.getInventory()./*? if >=1.21.5 {*/setSelectedSlot(/*?} else {*//*selected = (*//*?}*/previousSlot);
                        complete = true;
                    }
                }
            }
            case DONE -> complete = true;
        }
    }

    private void advanceToNextCandidate(LocalPlayer player) {
        int slot = candidateSlots.get(currentCandidateIndex);
        player.getInventory()./*? if >=1.21.5 {*/setSelectedSlot(/*?} else {*//*selected = (*//*?}*/slot);
        phase = Phase.SWITCHING;
        ticksInPhase = 0;
    }

    /**
     * Resume scanning after a ContainerWalkTask failed (GUI had no game-mode match).
     * Closes the current GUI, advances to the next candidate, and restarts.
     * @return true if there are more candidates to try, false if exhausted
     */
    public boolean resumeFromFailedGui() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.screen != null && player != null) {
            player.closeContainer();
        }

        currentCandidateIndex++;
        if (currentCandidateIndex < candidateSlots.size()) {
            complete = false;
            resuming = true;
            if (player != null) {
                advanceToNextCandidate(player);
            }
            return true;
        }

        // All candidates exhausted
        if (player != null) {
            player.getInventory()./*? if >=1.21.5 {*/setSelectedSlot(/*?} else {*//*selected = (*//*?}*/previousSlot);
        }
        complete = true;
        return false;
    }

    /** Whether there are more candidate items to try after the current one. */
    public boolean hasMoreCandidates() {
        return currentCandidateIndex + 1 < candidateSlots.size();
    }

    @Override
    public void abort() {
        complete = true;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.getInventory()./*? if >=1.21.5 {*/setSelectedSlot(/*?} else {*//*selected = (*//*?}*/previousSlot);
        }
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public String getDescription() {
        if (candidateSlots.isEmpty()) return "Hotbar item scan";
        int slot = currentCandidateIndex < candidateSlots.size()
                ? candidateSlots.get(currentCandidateIndex) : -1;
        return "Hotbar item (slot " + slot + ", " + (currentCandidateIndex + 1) + "/" + candidateSlots.size() + ")";
    }

    /** Whether the hotbar scan found any candidate selector items. */
    public boolean hasFoundItems() { return foundItems; }

    @Override
    public boolean requiresLobby() { return true; }
}
