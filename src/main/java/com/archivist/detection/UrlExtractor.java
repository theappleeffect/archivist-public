package com.archivist.detection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts URLs, server addresses, and domains from text such as chat messages.
 * Supports homoglyph transliteration and NFKC normalization for obfuscated text.
 */
public final class UrlExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?<=[^a-zA-Z]|^)(?:https?://)?(?:www\\.)?([a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?)+)(?::\\d{1,5})?(?:/[\\w\\-./?%&=]*)?"
    );

    private static final Pattern METRIC_PATTERN = Pattern.compile(
            "^\\d+\\.\\d+[a-z]{0,5}$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "\\[([0-9a-fA-F:]+)](?::(\\d{1,5}))?"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\S+@\\S+\\.\\S+"
    );

    private static final Pattern SRV_PATTERN = Pattern.compile(
            "_minecraft\\._tcp\\.(.+)"
    );

    private static final Pattern DIGITS_AND_DOTS = Pattern.compile(
            "^[\\d.]+$"
    );

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    private static final Set<String> GAME_TLDS = Set.of(
            ".com", ".net", ".org", ".gg", ".io", ".co", ".xyz", ".me", ".cc", ".us"
    );

    private static final Set<String> STORE_PREFIXES = Set.of(
            "shop.", "buy.", "store.", "vote.", "wiki.", "map.", "status.", "forum.", "docs."
    );

    private static final Set<String> WEB_KEYWORDS = Set.of(
            "discord.gg", "discord.com", "google", "youtube", "twitch", "twitter", "reddit",
            "azure", "amazonaws", "cloudflare"
    );

    private static final Set<String> BLACKLIST = Set.of(
            "mojang.com", "minecraft.net", "microsoft.com", "localhost", "www.", "store.",
            "dyno.gg", "carl.gg", "mee6.gg", "cord.gg"
    );

    private static final Set<String> HIGH_PRIORITY_DOMAINS = Set.of(
            "minehut.gg", "minehut.com", "minehut", "fallentech.io", "mccentral.org"
    );

    private static final Map<Character, Character> HOMOGLYPH_MAP = new HashMap<>();

    static {
        // Cyrillic lowercase
        HOMOGLYPH_MAP.put('\u0430', 'a'); // а
        HOMOGLYPH_MAP.put('\u0435', 'e'); // е
        HOMOGLYPH_MAP.put('\u043E', 'o'); // о
        HOMOGLYPH_MAP.put('\u0440', 'p'); // р
        HOMOGLYPH_MAP.put('\u0441', 'c'); // с
        HOMOGLYPH_MAP.put('\u0445', 'x'); // х
        HOMOGLYPH_MAP.put('\u0443', 'y'); // у
        HOMOGLYPH_MAP.put('\u0456', 'i'); // і
        HOMOGLYPH_MAP.put('\u0455', 's'); // ѕ

        // Cyrillic uppercase
        HOMOGLYPH_MAP.put('\u0410', 'A'); // А
        HOMOGLYPH_MAP.put('\u0412', 'B'); // В
        HOMOGLYPH_MAP.put('\u0415', 'E'); // Е
        HOMOGLYPH_MAP.put('\u041A', 'K'); // К
        HOMOGLYPH_MAP.put('\u041C', 'M'); // М
        HOMOGLYPH_MAP.put('\u041D', 'H'); // Н
        HOMOGLYPH_MAP.put('\u041E', 'O'); // О
        HOMOGLYPH_MAP.put('\u0420', 'P'); // Р
        HOMOGLYPH_MAP.put('\u0421', 'C'); // С
        HOMOGLYPH_MAP.put('\u0422', 'T'); // Т
        HOMOGLYPH_MAP.put('\u0425', 'X'); // Х
        HOMOGLYPH_MAP.put('\u0406', 'I'); // І

        // IPA small capitals
        HOMOGLYPH_MAP.put('\u1D00', 'a'); // ᴀ
        HOMOGLYPH_MAP.put('\u0299', 'b'); // ʙ
        HOMOGLYPH_MAP.put('\u1D04', 'c'); // ᴄ
        HOMOGLYPH_MAP.put('\u1D05', 'd'); // ᴅ
        HOMOGLYPH_MAP.put('\u1D07', 'e'); // ᴇ
        HOMOGLYPH_MAP.put('\u0262', 'g'); // ɢ
        HOMOGLYPH_MAP.put('\u029C', 'h'); // ʜ
        HOMOGLYPH_MAP.put('\u026A', 'i'); // ɪ
        HOMOGLYPH_MAP.put('\u1D0A', 'j'); // ᴊ
        HOMOGLYPH_MAP.put('\u1D0B', 'k'); // ᴋ
        HOMOGLYPH_MAP.put('\u029F', 'l'); // ʟ
        HOMOGLYPH_MAP.put('\u1D0D', 'm'); // ᴍ
        HOMOGLYPH_MAP.put('\u0274', 'n'); // ɴ
        HOMOGLYPH_MAP.put('\u1D0F', 'o'); // ᴏ
        HOMOGLYPH_MAP.put('\u1D18', 'p'); // ᴘ
        HOMOGLYPH_MAP.put('\u0280', 'r'); // ʀ
        HOMOGLYPH_MAP.put('\u1D1B', 't'); // ᴛ
        HOMOGLYPH_MAP.put('\u1D1C', 'u'); // ᴜ
        HOMOGLYPH_MAP.put('\u1D20', 'v'); // ᴠ
        HOMOGLYPH_MAP.put('\u1D21', 'w'); // ᴡ
        HOMOGLYPH_MAP.put('\u028F', 'y'); // ʏ
        HOMOGLYPH_MAP.put('\u1D22', 'z'); // ᴢ

        // Unicode dot variants
        HOMOGLYPH_MAP.put('\u00B7', '.'); // ·
        HOMOGLYPH_MAP.put('\u2022', '.'); // •
        HOMOGLYPH_MAP.put('\u2024', '.'); // ․
        HOMOGLYPH_MAP.put('\uFF0E', '.'); // ．
    }

    private UrlExtractor() {}

    /**
     * Extracts and categorizes URLs, server addresses, and domains from text.
     * Tries raw text first, then NFKC normalization, then homoglyph transliteration.
     *
     * @param text the input text to scan
     * @return categorized extraction results
     */
    public static CategorizedResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return CategorizedResult.EMPTY;
        }

        CategorizedResult result = extractRaw(text);
        if (!result.isEmpty()) return result;

        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        result = extractRaw(normalized);
        if (!result.isEmpty()) return result;

        String transliterated = transliterateHomoglyphs(text);
        return extractRaw(transliterated);
    }

    /**
     * Transliterates known homoglyphs (Cyrillic, IPA small capitals, Unicode dots)
     * to their ASCII equivalents.
     */
    public static String transliterateHomoglyphs(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character replacement = HOMOGLYPH_MAP.get(ch);
            sb.append(replacement != null ? replacement : ch);
        }
        return sb.toString();
    }

    private static CategorizedResult extractRaw(String text) {
        List<String> gameAddresses = new ArrayList<>();
        List<String> detectedUrls = new ArrayList<>();
        List<String> versionStrings = new ArrayList<>();
        List<String> highPriorityMatches = new ArrayList<>();

        Matcher urlMatcher = URL_PATTERN.matcher(text);
        while (urlMatcher.find()) {
            String fullMatch = urlMatcher.group(0);
            String domain = urlMatcher.group(1).toLowerCase(Locale.ROOT);

            if (isBlacklisted(domain)) continue;
            if (EMAIL_PATTERN.matcher(fullMatch).matches()) continue;
            if (METRIC_PATTERN.matcher(domain).matches()) continue;
            if (Character.isDigit(domain.charAt(0))) {
                if (!IPV4_PATTERN.matcher(domain).matches()) continue;
            }

            Matcher srvMatcher = SRV_PATTERN.matcher(domain);
            if (srvMatcher.matches()) {
                gameAddresses.add(srvMatcher.group(1));
                continue;
            }

            for (String hpd : HIGH_PRIORITY_DOMAINS) {
                if (domain.contains(hpd)) {
                    highPriorityMatches.add(domain);
                    break;
                }
            }

            if (DIGITS_AND_DOTS.matcher(domain).matches()) {
                if (IPV4_PATTERN.matcher(domain).matches()) {
                    gameAddresses.add(fullMatch);
                } else {
                    versionStrings.add(domain);
                }
                continue;
            }

            boolean isStoreDomain = false;
            for (String prefix : STORE_PREFIXES) {
                if (domain.startsWith(prefix)) { isStoreDomain = true; break; }
            }
            if (isStoreDomain) {
                detectedUrls.add(fullMatch);
                continue;
            }

            if (isWebUrl(domain)) {
                detectedUrls.add(fullMatch);
            } else if (hasGameTld(domain)) {
                gameAddresses.add(domain);
            } else {
                detectedUrls.add(fullMatch);
            }
        }

        Matcher ipv6Matcher = IPV6_PATTERN.matcher(text);
        while (ipv6Matcher.find()) {
            String addr = ipv6Matcher.group(0);
            gameAddresses.add(addr);
        }

        gameAddresses.sort(Comparator.comparingInt(UrlExtractor::addressSortRank));

        if (gameAddresses.isEmpty() && detectedUrls.isEmpty()
                && versionStrings.isEmpty() && highPriorityMatches.isEmpty()) {
            return CategorizedResult.EMPTY;
        }

        return new CategorizedResult(
                List.copyOf(gameAddresses),
                List.copyOf(detectedUrls),
                List.copyOf(versionStrings),
                List.copyOf(highPriorityMatches)
        );
    }

    private static int addressSortRank(String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        if (lower.startsWith("play.")) return 0;
        if (lower.startsWith("mc.")) return 1;
        if (lower.startsWith("hub.")) return 2;
        if (IPV4_PATTERN.matcher(lower).matches()) return 99;
        return 50;
    }

    private static boolean isBlacklisted(String domain) {
        for (String bl : BLACKLIST) {
            if (domain.contains(bl)) return true;
        }
        return false;
    }

    private static boolean isWebUrl(String domain) {
        for (String keyword : WEB_KEYWORDS) {
            if (domain.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean hasGameTld(String domain) {
        for (String tld : GAME_TLDS) {
            if (domain.endsWith(tld)) return true;
        }
        return false;
    }

    /**
     * Categorized extraction results.
     *
     * @param gameAddresses      bare server addresses, sorted by prefix priority
     * @param detectedUrls       full URLs with protocol/path
     * @param versionStrings     digit-and-dot strings
     * @param highPriorityMatches matches against known high-priority domains
     */
    public record CategorizedResult(
            List<String> gameAddresses,
            List<String> detectedUrls,
            List<String> versionStrings,
            List<String> highPriorityMatches
    ) {
        static final CategorizedResult EMPTY = new CategorizedResult(
                List.of(), List.of(), List.of(), List.of()
        );

        boolean isEmpty() {
            return gameAddresses.isEmpty() && detectedUrls.isEmpty()
                    && versionStrings.isEmpty() && highPriorityMatches.isEmpty();
        }
    }
}
