package com.archivist.detection;

import java.util.Set;

public final class DetectionConstants {
    private DetectionConstants() {}

    public static final Set<String> IGNORED_NAMESPACES = Set.of(
            "minecraft", "fabric", "fabric-api", "fabricloader", "java", "c", "bukkit"
    );

    public static final Set<String> COMMON_WORDS = Set.of(
            "command", "commands", "buy", "vote", "hub", "afk", "score", "shards",
            "success", "jumps", "cosmetics", "glow", "fly", "chat", "shop", "join",
            "play", "spawn", "home", "warp", "help", "info", "list", "msg",
            "tell", "reply", "pay", "balance", "heal", "feed", "sell",
            "gamemode", "back", "top", "kit", "kits", "rank", "ranks",
            "tag", "tags", "menu", "settings", "options", "staff",
            "party", "friend", "friends", "ignore", "mute", "ban", "kick",
            "warn", "report", "mod", "admin", "build", "pvp",
            "lobby", "server", "servers", "queue", "leave", "switch",
            "compass", "navigate", "portal", "map", "quest", "quests",
            "event", "events", "coins", "gems", "tokens", "credits", "points",
            "level", "levels", "xp", "stats", "skill", "skills",
            "pet", "pets", "mount", "mounts", "trail", "trails",
            "title", "titles", "prefix", "nick", "skin",
            "enchant", "enchants", "craft", "recipe", "recipes",
            "trade", "market", "auction", "reward", "rewards",
            "daily", "crate", "crates", "key", "keys", "loot",
            "mine", "mines", "farm", "fish", "fishing", "hunt",
            "duel", "duels", "arena", "game", "games", "match",
            "team", "teams", "clan", "clans", "guild", "guilds",
            "plot", "plots", "claim", "claims", "trust", "region",
            "world", "worlds", "biome", "weather", "time", "day", "night",
            "hopper", "hoppers", "pouch", "pouches", "shout", "voucher",
            "discord", "rings", "clouds", "gm", "tp", "tpa"
    );

    public static final Set<String> CLIENT_MOD_NAMESPACES;
    static {
        Set<String> mods = new java.util.HashSet<>();
        try {
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                mods.add(mod.getMetadata().getId().toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignored) {}
        CLIENT_MOD_NAMESPACES = Set.copyOf(mods);
    }
}
