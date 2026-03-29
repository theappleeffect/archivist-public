package com.archivist.proxy;

import com.archivist.ArchivistMod;
import com.archivist.account.SecureStorage;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages SOCKS proxy profiles. Profiles are saved to proxy_config.json.
 * Actual proxy injection into Netty requires ConnectionProxyMixin + netty-handler-proxy dependency.
 */
public final class ProxyManager {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getGameDir()
            .resolve("archivist").resolve("proxy_config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<ProxyProfile> profiles = new ArrayList<>();
    private boolean proxyEnabled;
    private String activeProfileId;

    public List<ProxyProfile> getProfiles() { return List.copyOf(profiles); }
    public boolean isProxyEnabled() { return proxyEnabled; }
    public String getActiveProfileId() { return activeProfileId; }

    public Optional<ProxyProfile> getActiveProfile() {
        if (activeProfileId == null || !proxyEnabled) return Optional.empty();
        return profiles.stream().filter(p -> p.id.equals(activeProfileId) && p.enabled).findFirst();
    }

    public void setProxyEnabled(boolean enabled) {
        this.proxyEnabled = enabled;
        save();
    }

    public void setActiveProfile(String id) {
        this.activeProfileId = id;
        save();
    }

    // ── Load / Save ──

    public void load() {
        profiles.clear();
        proxyEnabled = false;
        activeProfileId = null;
        if (!Files.exists(CONFIG_PATH)) return;

        try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            proxyEnabled = root.has("proxyEnabled") && root.get("proxyEnabled").getAsBoolean();
            if (root.has("activeProfileId") && !root.get("activeProfileId").isJsonNull()) {
                activeProfileId = root.get("activeProfileId").getAsString();
            }
            if (root.has("profiles")) {
                for (JsonElement el : root.getAsJsonArray("profiles")) {
                    ProxyProfile profile = GSON.fromJson(el, ProxyProfile.class);
                    profiles.add(profile);
                }
            }
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to load proxy_config.json", e);
        }
    }

    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (!Files.exists(parent)) Files.createDirectories(parent);

            JsonObject root = new JsonObject();
            root.addProperty("proxyEnabled", proxyEnabled);
            root.addProperty("activeProfileId", activeProfileId);
            root.add("profiles", GSON.toJsonTree(profiles));

            Path tmp = CONFIG_PATH.resolveSibling("proxy_config.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to save proxy_config.json", e);
        }
    }

    // ── Profile management ──

    public void addProfile(ProxyProfile profile) {
        profiles.add(profile);
        if (activeProfileId == null) activeProfileId = profile.id;
        save();
    }

    public void removeProfile(String id) {
        profiles.removeIf(p -> p.id.equals(id));
        if (id.equals(activeProfileId)) {
            activeProfileId = profiles.isEmpty() ? null : profiles.getFirst().id;
        }
        save();
    }

    public void updateProfile(ProxyProfile updated) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(updated.id)) {
                profiles.set(i, updated);
                break;
            }
        }
        save();
    }

    /**
     * Gets the decoded password for the active proxy profile.
     */
    public String getActivePassword() {
        return getActiveProfile()
                .filter(p -> p.passwordEncoded != null && !p.passwordEncoded.isEmpty())
                .map(p -> SecureStorage.decrypt(p.passwordEncoded))
                .orElse(null);
    }

    /**
     * Sets and encrypts the password for a profile.
     */
    public void setProfilePassword(String profileId, String password) {
        for (ProxyProfile p : profiles) {
            if (p.id.equals(profileId)) {
                p.passwordEncoded = SecureStorage.encrypt(password);
                break;
            }
        }
        save();
    }
}
