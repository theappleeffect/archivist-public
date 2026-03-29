package com.archivist.detection;

import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.border.WorldBorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Weighted multi-signal lobby/hub detector. Each signal pushes the score
 * toward "lobby" (low confidence) or "real server" (high confidence).
 * Final score = clamp(0.5 + serverTotal - lobbyTotal, 0.05, 1.0).
 */
public final class SessionConfidence {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist/confidence");

    // ── Signal Record ──

    public record Signal(double weight, String reason) {}

    // ── Command Sets ──

    private static final Set<String> HUB_COMMANDS = Set.of(
            "hub", "lobby", "server", "play", "queue", "leave",
            "servers", "selector", "compass", "navigate", "join",
            "switch", "transfer", "network", "portal", "menu"
    );

    private static final Set<String> SURVIVAL_COMMANDS = Set.of(
            "sethome", "delhome", "tpa", "tpahere", "tpaccept", "tpdeny",
            "setwarp", "claim", "unclaim", "trust", "untrust", "abandonclaim",
            "sell", "ah", "auctionhouse", "market"
    );

    private static final Set<String> LOBBY_TAB_KEYWORDS = Set.of(
            "lobby", "hub", "queue", "play now", "network", "select",
            "choose", "click to", "server", "join a", "minigame"
    );

    private static final Set<String> LOBBY_CHAT_KEYWORDS = Set.of(
            "click to play", "select a game", "join the", "back to hub",
            "teleporting to lobby", "sending you to", "welcome to the hub",
            "click here to play", "/play", "choose a mode",
            "join a server", "enter a portal", "use /server", "/server",
            "choose a server", "pick a game", "welcome to"
    );

    // "You were sent to X" transfer messages — very strong lobby indicator
    private static final Set<String> TRANSFER_CHAT_KEYWORDS = Set.of(
            "you were sent to", "sending you to", "connecting to",
            "transferring to", "teleporting to", "you have been sent to",
            "moving you to", "joining server", "connected to"
    );

    // Item display name keywords that scream "lobby"
    private static final Set<String> LOBBY_ITEM_NAME_KEYWORDS = Set.of(
            "selector", "navigator", "lobby", "hub", "server", "play",
            "profile", "cosmetic", "gadget", "settings", "shop", "store",
            "game menu", "game selector", "server selector", "teleporter",
            "compass", "leave", "quit", "back to", "cosmetics", "pets",
            "trails", "particles", "visibility", "players", "friends"
    );

    private static final Set<String> SCOREBOARD_LOBBY_KEYWORDS = Set.of(
            "lobby", "hub", "global players", "play.", "queue", "mini-game",
            "minigame", "select", "game mode", "bedwars", "skywars", "kitpvp",
            "practice", "duels", "events", "network",
            "server", "portal", "join", "players online", "online:"
    );

    // ── State ──

    private final CopyOnWriteArrayList<Signal> lobbySignals = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Signal> serverSignals = new CopyOnWriteArrayList<>();

    private boolean transferred;
    private int commandTreeCount;
    private long joinTimestamp;

    // One-shot guards
    private volatile boolean commandsProcessed;
    private volatile boolean tabListProcessed;
    private volatile boolean inventoryChecked;
    private volatile boolean worldPropertiesChecked;
    private volatile boolean playerCountProcessed;
    private volatile boolean scoreboardProcessed;

    private int inventoryCheckDelay;

    // Chat keyword cap
    private double chatLobbyAccumulated;
    private static final double MAX_CHAT_LOBBY = 0.12;

    // Gamemode tracking for combo signals
    private boolean adventureModeDetected;

    // Hard overrides — deterministic rules that guarantee classification
    private volatile boolean forceLobby;
    private volatile boolean forceReal;

    // Command count tracking for NPC fallback decision
    private boolean lowCommandCount;

    // ── Lifecycle ──

    public void reset() {
        lobbySignals.clear();
        serverSignals.clear();
        transferred = false;
        commandTreeCount = 0;
        joinTimestamp = System.currentTimeMillis();
        commandsProcessed = false;
        tabListProcessed = false;
        inventoryChecked = false;
        worldPropertiesChecked = false;
        playerCountProcessed = false;
        scoreboardProcessed = false;
        inventoryCheckDelay = 0;
        chatLobbyAccumulated = 0;
        adventureModeDetected = false;
        forceLobby = false;
        forceReal = false;
        lowCommandCount = false;
    }

    // ── Signal Helpers ──

    private void addLobbySignal(double weight, String reason) {
        lobbySignals.add(new Signal(weight, reason));
    }

    private void addServerSignal(double weight, String reason) {
        serverSignals.add(new Signal(weight, reason));
    }

    // ── Signal Handlers ──

    public void onDomainKnown(String domain) {
        // No-op: proxy domain detection removed
    }

    public void onBrandReceived(String rawBrand) {
        // No-op: proxy/backend brand detection removed
    }

    /**
     * @return true if this is a transfer (second+ command tree)
     */
    public boolean onCommandTree() {
        commandTreeCount++;
        if (commandTreeCount > 1) {
            transferred = true;
            // Clear all signals — new server, fresh evaluation
            lobbySignals.clear();
            serverSignals.clear();
            chatLobbyAccumulated = 0;
            // Reset one-shot guards so new signals can fire
            commandsProcessed = false;
            tabListProcessed = false;
            inventoryChecked = false;
            worldPropertiesChecked = false;
            playerCountProcessed = false;
            scoreboardProcessed = false;
            inventoryCheckDelay = 0;
            forceLobby = false;
            forceReal = false;
            lowCommandCount = false;
            joinTimestamp = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    public void onCommandsDetected(Set<String> commands) {
        if (commandsProcessed || commands == null || commands.isEmpty()) return;
        commandsProcessed = true;

        int total = commands.size();
        int survivalCount = 0;
        boolean hasServerCmd = false;
        boolean hasLobbyCmd = false;
        List<String> matchedSurvival = new java.util.ArrayList<>();

        for (String cmd : commands) {
            String lower = cmd.toLowerCase(Locale.ROOT);
            if (lower.contains(":")) lower = lower.substring(lower.indexOf(':') + 1);
            if (SURVIVAL_COMMANDS.contains(lower)) {
                survivalCount++;
                matchedSurvival.add(lower);
            }
            if ("server".equals(lower) || "servers".equals(lower)) hasServerCmd = true;
            if ("lobby".equals(lower) || "hub".equals(lower)) hasLobbyCmd = true;
        }

        LOGGER.info("[DEBUG] onCommandsDetected: total={}, survivalCount={}, matched={}, hasServer={}, hasLobby={}, adventureMode={}",
                total, survivalCount, matchedSurvival, hasServerCmd, hasLobbyCmd, adventureModeDetected);

        // Track low command count
        if (total < 15 && total > 0) {
            lowCommandCount = true;
            LOGGER.info("[DEBUG] lowCommandCount=true (total {} < 15)", total);
        }

        // ── Hard lobby rules ──

        // ≤10 commands + adventure mode = 100% lobby
        if (total <= 10 && total > 0 && adventureModeDetected) {
            LOGGER.info("[DEBUG] FORCE LOBBY: {} commands + adventure mode", total);
            setForceLobby("cmd: " + total + " commands + adventure mode");
        }

        // ≤10 commands + /server or /lobby present = 100% lobby
        if (total <= 10 && total > 0 && (hasServerCmd || hasLobbyCmd)) {
            LOGGER.info("[DEBUG] FORCE LOBBY: {} commands + /server or /lobby", total);
            setForceLobby("cmd: " + total + " commands + /server or /lobby present");
        }

        // ── Hard real server rule ──

        // 4+ survival commands = 100% real server
        if (survivalCount >= 4) {
            LOGGER.info("[DEBUG] FORCE REAL: {} survival commands: {}", survivalCount, matchedSurvival);
            setForceReal("cmd: " + survivalCount + " survival commands (home, tpa, shop, etc.)");
        }
    }

    public void onGamemode(String gamemode) {
        if (gamemode == null) return;
        LOGGER.info("[DEBUG] onGamemode: {}", gamemode);
        switch (gamemode.toUpperCase(Locale.ROOT)) {
            case "SURVIVAL" -> {} // neutral — most servers AND some lobbies use survival
            case "ADVENTURE" -> { adventureModeDetected = true; addLobbySignal(0.25, "gamemode: ADVENTURE"); }
            case "CREATIVE" -> {} // neutral
            case "SPECTATOR" -> {} // neutral
        }
    }

    public void onTabList(String header, String footer) {
        if (tabListProcessed) return;
        tabListProcessed = true;

        // String combined = ((header != null ? header : "") + " " + (footer != null ? footer : ""))
        //         .toLowerCase(Locale.ROOT);
        // if (combined.isBlank()) return;
        //
        // int lobbyHits = 0;
        // for (String kw : LOBBY_TAB_KEYWORDS) {
        //     if (combined.contains(kw)) lobbyHits++;
        // }
        //
        // // Direct "lobby" or "hub" mention in tab
        // if (combined.contains("lobby") || combined.contains("hub")) {
        //     addLobbySignal(0.10, "tablist: direct 'lobby'/'hub' mention");
        // }
        // if (lobbyHits > 0) {
        //     addLobbySignal(Math.min(0.15, lobbyHits * 0.05), "tablist: " + lobbyHits + " lobby keywords");
        // }
    }

    public void onPlayerCount(int advertisedCount, int tabListCount) {
        if (playerCountProcessed) return;
        playerCountProcessed = true;

        // // Lobby signal: many players advertised but very few visible in tab list
        // if (advertisedCount > 50 && tabListCount < 5) {
        //     addLobbySignal(0.12, "players: " + advertisedCount + " advertised but only " + tabListCount + " in tab");
        // }
    }

    public void onChatMessage(String message) {
        if (message == null) return;
        // String lower = message.toLowerCase(Locale.ROOT);
        //
        // // Transfer messages — strong lobby signal (separate from chat keyword cap)
        // for (String kw : TRANSFER_CHAT_KEYWORDS) {
        //     if (lower.contains(kw)) {
        //         addLobbySignal(0.15, "transfer: \"" + kw + "\"");
        //         break;
        //     }
        // }
        //
        // // Regular lobby chat keywords (capped)
        // if (chatLobbyAccumulated < MAX_CHAT_LOBBY) {
        //     for (String kw : LOBBY_CHAT_KEYWORDS) {
        //         if (lower.contains(kw)) {
        //             double weight = Math.min(0.04, MAX_CHAT_LOBBY - chatLobbyAccumulated);
        //             if (weight > 0) {
        //                 addLobbySignal(weight, "chat: \"" + kw + "\"");
        //                 chatLobbyAccumulated += weight;
        //             }
        //             break;
        //         }
        //     }
        // }
    }

    /**
     * Analyze scoreboard sidebar lines for lobby indicators.
     * Called from client tick after a delay for the scoreboard to populate.
     */
    public void onScoreboardSidebar(List<String> lines) {
        if (scoreboardProcessed || lines == null || lines.isEmpty()) return;
        scoreboardProcessed = true;

        // String combined = String.join(" ", lines).toLowerCase(Locale.ROOT);
        //
        // // Direct "lobby" or "hub" mention in scoreboard = strong signal
        // if (combined.contains("lobby") || combined.contains("hub")) {
        //     addLobbySignal(0.20, "scoreboard: direct 'lobby'/'hub' mention");
        // }
        //
        // // Gamemode count lines (e.g. "Lobby: 16", "SkyWars: 45", "Practice: 63")
        // // Pattern: word(s) followed by colon and number — lobby-exclusive display
        // int gamemodeCountLines = 0;
        // for (String line : lines) {
        //     String trimmed = line.trim();
        //     if (trimmed.matches("(?i).+:\\s*\\d+.*") && !trimmed.contains("http")) {
        //         gamemodeCountLines++;
        //     }
        // }
        // if (gamemodeCountLines >= 2) {
        //     addLobbySignal(0.15, "scoreboard: " + gamemodeCountLines + " gamemode count lines");
        // }
        //
        // // Also feed scoreboard text through chat keyword matching
        // for (String line : lines) {
        //     onChatMessage(line);
        // }
    }

    /**
     * Check player inventory for lobby indicators. Called from client tick.
     * Waits 2 seconds after join for inventory to populate.
     */
    public void onInventoryTick(Player player) {
        if (inventoryChecked || player == null) return;
        inventoryCheckDelay++;
        if (inventoryCheckDelay < 40) return; // 2 second delay
        inventoryChecked = true;

        Inventory inv = player.getInventory();
        LOGGER.info("[DEBUG] onInventoryTick: checking hotbar");

        boolean hasLobbyKeywordName = false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();

            // Lobby selector item TYPES → force lobby
            if (item == Items.COMPASS || item == Items.CLOCK
                    || item == Items.NETHER_STAR || item == Items.PLAYER_HEAD) {
                String typeName = item == Items.COMPASS ? "compass" : item == Items.CLOCK ? "clock"
                        : item == Items.NETHER_STAR ? "nether star" : "player head";
                LOGGER.info("[DEBUG] FORCE LOBBY: has item type: {}", typeName);
                setForceLobby("has item: " + typeName);
            }

            // Lobby selector item NAMES → force lobby
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
                String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                for (String kw : LOBBY_ITEM_NAME_KEYWORDS) {
                    if (name.contains(kw)) {
                        hasLobbyKeywordName = true;
                        LOGGER.info("[DEBUG] FORCE LOBBY: item name '{}' matches keyword '{}'",
                                stack.getHoverName().getString(), kw);
                        setForceLobby("has item named: \"" + stack.getHoverName().getString() + "\"");
                        break;
                    }
                }
            }
        }

        LOGGER.info("[DEBUG] hotbar scan done: forceLobby={}, forceReal={}, hasLobbyKeywordName={}",
                forceLobby, forceReal, hasLobbyKeywordName);
    }

    /**
     * Check world properties for lobby indicators. Called from client tick.
     */
    public void onWorldProperties(Minecraft mc) {
        if (worldPropertiesChecked || mc.level == null) return;
        worldPropertiesChecked = true;

        // // Difficulty
        // if (mc.level.getDifficulty() == Difficulty.PEACEFUL) {
        //     addLobbySignal(0.08, "world: PEACEFUL difficulty");
        // }
        //
        // // World border
        // WorldBorder border = mc.level.getWorldBorder();
        // double size = border.getSize();
        // if (size < 256) {
        //     addLobbySignal(0.10, "world: border " + (int) size + " blocks");
        // }
    }

    /**
     * Called every client tick.
     */
    public void tick() {
        // No per-tick signals remaining
    }

    // ── Force Lobby ──

    /** Hard override: set this server as 100% lobby. */
    public void setForceLobby(String reason) {
        forceLobby = true;
        addLobbySignal(0.50, "FORCE LOBBY: " + reason);
    }

    /** Hard override: set this server as 100% real. */
    public void setForceReal(String reason) {
        forceReal = true;
        addServerSignal(0.50, "FORCE REAL: " + reason);
    }

    public boolean isForceLobby() { return forceLobby; }
    public boolean isForceReal() { return forceReal; }
    public boolean isAdventureModeDetected() { return adventureModeDetected; }
    public boolean isLowCommandCount() { return lowCommandCount; }

    // ── Score Computation ──

    public double getConfidence() {
        if (forceLobby) {
            LOGGER.debug("[DEBUG] getConfidence: forceLobby=true → 0.05");
            return 0.05;
        }
        if (forceReal) {
            LOGGER.debug("[DEBUG] getConfidence: forceReal=true → 0.95");
            return 0.95;
        }

        var lobbySnap = List.copyOf(lobbySignals);
        var serverSnap = List.copyOf(serverSignals);

        double lobbyTotal = 0;
        for (Signal s : lobbySnap) lobbyTotal += s.weight();

        double serverTotal = 0;
        for (Signal s : serverSnap) serverTotal += s.weight();

        if (transferred) {
            // Start at 0.95: after a proxy transfer, assume real server until lobby
            // signals actively push confidence down. Proxies route to game servers,
            // so the transferred destination is overwhelmingly likely to be real.
            return Math.max(0.05, Math.min(1.0, 0.95 + serverTotal - lobbyTotal));
        }

        return Math.max(0.05, Math.min(1.0, 0.5 + serverTotal - lobbyTotal));
    }

    public double effective(double rawConfidence) {
        return rawConfidence * getConfidence();
    }

    // ── Query ──

    public boolean hasTransferred() { return transferred; }
    public boolean isProxyDetected() { return false; }
    public List<Signal> getLobbySignals() { return List.copyOf(lobbySignals); }
    public List<Signal> getServerSignals() { return List.copyOf(serverSignals); }

    /**
     * Checks if an item is a tool, weapon, or armor by registry name pattern.
     */
    private static boolean isToolOrWeapon(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe")
                || id.endsWith("_shovel") || id.endsWith("_hoe") || id.equals("bow")
                || id.equals("crossbow") || id.equals("shield") || id.equals("trident")
                || id.equals("mace")
                || id.endsWith("_helmet") || id.endsWith("_chestplate")
                || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    /**
     * Human-readable breakdown for GUI/logging.
     */
    public String getConfidenceBreakdown() {
        StringBuilder sb = new StringBuilder();
        double conf = getConfidence();
        String label = conf > 0.7 ? "REAL SERVER" : conf > 0.4 ? "UNCERTAIN" : "LOBBY LIKELY";
        sb.append(String.format("Session: %.0f%% (%s)\n", conf * 100, label));

        if (!lobbySignals.isEmpty()) {
            sb.append("  Lobby signals:\n");
            for (Signal s : lobbySignals) {
                sb.append(String.format("    -%.2f  %s\n", s.weight(), s.reason()));
            }
        }
        if (!serverSignals.isEmpty()) {
            sb.append("  Server signals:\n");
            for (Signal s : serverSignals) {
                sb.append(String.format("    +%.2f  %s\n", s.weight(), s.reason()));
            }
        }
        return sb.toString();
    }
}
