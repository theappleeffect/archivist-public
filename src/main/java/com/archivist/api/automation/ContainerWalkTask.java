package com.archivist.api.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans an open container GUI for game-mode items (survival, SMP, SkyBlock, etc.)
 * and clicks the best match to transfer the player to a real server.
 *
 * <p>Priority: item with the highest player count ({@code >= 22}) among keyword matches.
 * If no counts, clicks the first keyword match. If nothing matches, marks as failed.</p>
 */
public class ContainerWalkTask implements TickTask {

    private enum Phase { WAIT_GUI, SCANNING, CLICKING, WAIT_TRANSFER, CLOSING, DONE }

    // Game mode keywords to look for in item names and lore
    private static final Set<String> GAMEMODE_KEYWORDS = Set.of(
            "survival", "smp", "skyblock", "sky block",
            "factions", "prison", "bedwars", "bed wars",
            "kitpvp", "kit pvp", "creative", "minigames", "mini games",
            "practice", "lifesteal", "life steal", "towny", "anarchy",
            "uhc", "hardcore", "oneblock", "one block",
            "skywars", "sky wars", "eggwars", "egg wars",
            "duels", "pvp", "parkour", "hunger games", "hungergames",
            "op prison", "op factions", "earth", "vanilla",
            "gamemode", "game mode", "gamemode selector"
    );

    // Safety net — never click these
    private static final Set<String> CLICK_BLACKLIST = Set.of(
            "buy", "purchase", "cost", "price", "confirm",
            "accept", "pay", "redeem", "claim", "withdraw", "deposit"
    );

    private static final int GUI_WAIT_TICKS = 15; // 0.75s fallback
    private static final int TRANSFER_WAIT_TICKS = 20; // 1s fallback

    private Phase phase = Phase.WAIT_GUI;
    private int ticksInPhase = 0;
    private boolean complete = false;
    private boolean failed = false;
    private int targetSlot = -1;

    public ContainerWalkTask() {}

    /** @deprecated maxDepth is no longer used; kept for backwards compatibility. */
    @Deprecated
    public ContainerWalkTask(int maxDepth) { this(); }

    @Override
    public void start(Minecraft mc) {
        phase = Phase.WAIT_GUI;
        ticksInPhase = 0;
        failed = false;
        targetSlot = -1;
    }

    @Override
    public void tick(Minecraft mc) {
        if (complete) return;
        ticksInPhase++;
        LocalPlayer player = mc.player;
        if (player == null) { complete = true; return; }

        switch (phase) {
            case WAIT_GUI -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    phase = Phase.SCANNING;
                    ticksInPhase = 0;
                } else if (ticksInPhase >= GUI_WAIT_TICKS) {
                    failed = true;
                    complete = true;
                }
            }
            case SCANNING -> {
                // Give the GUI a moment to populate
                if (ticksInPhase < 5) return;

                if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
                    failed = true;
                    complete = true;
                    return;
                }

                AbstractContainerMenu menu = screen.getMenu();

                if (menu.slots == null) {
                    failed = true;
                    complete = true;
                    return;
                }

                int bestSlot = -1;
                int bestPlayerCount = -1;
                int firstKeywordSlot = -1;

                for (Slot slot : menu.slots) {
                    ItemStack stack = slot.getItem();
                    if (stack.isEmpty() || isFiller(stack)) continue;

                    String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                    List<String> lore = getLore(stack);
                    String combined = name + " " + String.join(" ", lore);

                    // Skip blacklisted items
                    if (CLICK_BLACKLIST.stream().anyMatch(combined::contains)) continue;

                    // Check for game mode keyword match
                    boolean hasKeyword = GAMEMODE_KEYWORDS.stream().anyMatch(combined::contains);
                    if (!hasKeyword) continue;

                    // Extract player count
                    int playerCount = extractPlayerCount(combined);
                    if (playerCount >= 22 && playerCount > bestPlayerCount) {
                        bestSlot = slot.index;
                        bestPlayerCount = playerCount;
                    }

                    if (firstKeywordSlot == -1) {
                        firstKeywordSlot = slot.index;
                    }
                }

                // Priority: highest player count, else first keyword match
                targetSlot = bestSlot >= 0 ? bestSlot : firstKeywordSlot;

                if (targetSlot >= 0) {
                    phase = Phase.CLICKING;
                    ticksInPhase = 0;
                } else {
                    // Nothing matched — close and fail
                    phase = Phase.CLOSING;
                    ticksInPhase = 0;
                    failed = true;
                }
            }
            case CLICKING -> {
                // Brief randomized delay before click
                int delay = JitteredTimer.msToTicks(JitteredTimer.nextDelay(100, 300));
                if (ticksInPhase < delay) return;

                if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
                    complete = true;
                    return;
                }

                if (mc.gameMode != null) {
                    mc.gameMode.handleInventoryMouseClick(
                            screen.getMenu().containerId, targetSlot, 0, ClickType.PICKUP, player);
                }

                phase = Phase.WAIT_TRANSFER;
                ticksInPhase = 0;
            }
            case WAIT_TRANSFER -> {
                // Wait for server transfer or GUI to close
                if (mc.screen == null || !(mc.screen instanceof AbstractContainerScreen<?>)) {
                    // GUI closed — likely transferred or item consumed
                    complete = true;
                    return;
                }
                if (ticksInPhase >= TRANSFER_WAIT_TICKS) {
                    // Timed out waiting — close and finish
                    phase = Phase.CLOSING;
                    ticksInPhase = 0;
                }
            }
            case CLOSING -> {
                LocalPlayer p = mc.player;
                if (p != null) p.closeContainer();
                complete = true;
            }
            case DONE -> complete = true;
        }
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");

    /**
     * Extract the largest integer >= 22 from text, or -1 if none found.
     * Threshold of 22 avoids version numbers (1.8, 1.21.11 — max component is 21).
     */
    private static int extractPlayerCount(String text) {
        int max = -1;
        Matcher m = NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            try {
                int val = Integer.parseInt(m.group(1));
                if (val >= 22 && val > max) max = val;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    private static boolean isFiller(ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.endsWith("_glass_pane") || id.equals("glass_pane");
    }

    private static List<String> getLore(ItemStack stack) {
        List<String> lore = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            var context = mc.level != null ? mc.level.registryAccess() : null;
            if (context != null && mc.player != null) {
                var lines = stack.getTooltipLines(
                        net.minecraft.world.item.Item.TooltipContext.of(mc.level),
                        mc.player, net.minecraft.world.item.TooltipFlag.NORMAL);
                for (int i = 1; i < lines.size(); i++) {
                    lore.add(lines.get(i).getString().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {}
        return lore;
    }

    @Override
    public void abort() {
        complete = true;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.closeContainer();
    }

    @Override
    public boolean isComplete() { return complete; }

    /** Whether the scan found no matching game-mode items. */
    public boolean hasFailed() { return failed; }

    @Override
    public String getDescription() {
        return targetSlot >= 0 ? "GUI scan (clicking slot " + targetSlot + ")" : "GUI scan";
    }

    @Override
    public boolean requiresLobby() { return true; }
}
