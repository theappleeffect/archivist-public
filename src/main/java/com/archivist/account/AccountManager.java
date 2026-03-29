package com.archivist.account;

import com.archivist.ArchivistMod;
import com.archivist.mixin.accessor.MinecraftUserAccessor;
import com.archivist.util.ArchivistExecutor;
import com.google.gson.*;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.Reader;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages multiple Minecraft accounts. Supports Microsoft OAuth device code flow,
 * cracked (offline), and raw token accounts. Tokens encrypted via AES-256-GCM.
 * Account switching at runtime via MinecraftUserAccessor mixin.
 */
public final class AccountManager {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getGameDir()
            .resolve("archivist").resolve("accounts.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Microsoft OAuth constants
    private static final String MS_CLIENT_ID = "00000000402b5328";
    private static final String MS_DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String MS_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL = "https://api.minecraftservices.com/authentication/loginWithXbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String MS_SCOPE = "XboxLive.signin offline_access";
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final long TOKEN_REFRESH_THRESHOLD = 82800_000L; // 23 hours

    private final List<AccountEntry> accounts = new ArrayList<>();
    private String activeAccountId;
    private String originalAccountId;
    private final HttpClient httpClient;

    // OAuth state
    private volatile boolean oauthInProgress;
    private volatile String deviceCode;
    private volatile String userCode;
    private volatile String verificationUri;

    public AccountManager() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<AccountEntry> getAccounts() { return List.copyOf(accounts); }
    public String getActiveAccountId() { return activeAccountId; }
    public boolean isOAuthInProgress() { return oauthInProgress; }
    public String getUserCode() { return userCode; }
    public String getVerificationUri() { return verificationUri; }

    public Optional<AccountEntry> getActiveAccount() {
        if (activeAccountId == null) return Optional.empty();
        return accounts.stream().filter(a -> a.id.equals(activeAccountId)).findFirst();
    }

    public Optional<AccountEntry> getAccount(String id) {
        return accounts.stream().filter(a -> a.id.equals(id)).findFirst();
    }

    // ── Load / Save ──

    public void load() {
        accounts.clear();
        activeAccountId = null;
        if (!Files.exists(CONFIG_PATH)) return;

        try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("activeAccountId") && !root.get("activeAccountId").isJsonNull()) {
                activeAccountId = root.get("activeAccountId").getAsString();
            }
            if (root.has("accounts")) {
                for (JsonElement el : root.getAsJsonArray("accounts")) {
                    AccountEntry entry = GSON.fromJson(el, AccountEntry.class);
                    accounts.add(entry);
                }
            }
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to load accounts.json", e);
        }
    }

    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (!Files.exists(parent)) Files.createDirectories(parent);

            JsonObject root = new JsonObject();
            root.addProperty("activeAccountId", activeAccountId);
            root.add("accounts", GSON.toJsonTree(accounts));

            Path tmp = CONFIG_PATH.resolveSibling("accounts.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            ArchivistMod.LOGGER.warn("Failed to save accounts.json", e);
        }
    }

    // ── Capture original account on first load ──

    public void captureOriginalAccount() {
        Minecraft mc = Minecraft.getInstance();
        User current = mc.getUser();
        if (current == null) return;

        // Check if we already have the original
        for (AccountEntry a : accounts) {
            if (a.isOriginal) return;
        }

        AccountEntry original = new AccountEntry(
                current.getName(),
                current.getProfileId().toString(),
                AccountEntry.AccountType.MICROSOFT
        );
        original.isOriginal = true;
        original.accessTokenEncoded = SecureStorage.encrypt(current.getAccessToken());
        original.lastRefreshed = System.currentTimeMillis();
        accounts.add(original);
        if (activeAccountId == null) activeAccountId = original.id;
        originalAccountId = original.id;
        save();
    }

    // ── Account Management ──

    public void addCrackedAccount(String username) {
        AccountEntry entry = new AccountEntry(username,
                "00000000-0000-0000-0000-000000000000",
                AccountEntry.AccountType.CRACKED);
        accounts.add(entry);
        if (activeAccountId == null) activeAccountId = entry.id;
        save();
    }

    public void addTokenAccount(String username, String accessToken) {
        AccountEntry entry = new AccountEntry(username, "", AccountEntry.AccountType.TOKEN);
        entry.accessTokenEncoded = SecureStorage.encrypt(accessToken);
        accounts.add(entry);
        save();
    }

    public void removeAccount(String id) {
        // Don't remove original account
        for (AccountEntry a : accounts) {
            if (a.id.equals(id) && a.isOriginal) return;
        }
        accounts.removeIf(a -> a.id.equals(id));
        if (id.equals(activeAccountId)) {
            activeAccountId = accounts.isEmpty() ? null : accounts.getFirst().id;
        }
        save();
    }

    // ── Account Switching ──

    /**
     * Switch the active Minecraft session to the given account.
     * Uses MinecraftUserAccessor mixin to swap user/apiService/profileKeyPair.
     *
     * @param accountId the account ID to switch to
     * @param statusCallback receives status updates
     */
    public void switchAccount(String accountId, Consumer<String> statusCallback) {
        Optional<AccountEntry> opt = getAccount(accountId);
        if (opt.isEmpty()) {
            statusCallback.accept("Account not found");
            return;
        }

        AccountEntry entry = opt.get();
        Minecraft mc = Minecraft.getInstance();
        MinecraftUserAccessor accessor = (MinecraftUserAccessor) mc;

        try {
            switch (entry.type) {
                case CRACKED -> {
                    // Offline mode — create a User with offline UUID
                    //? if >=1.21.9 {
                    User offlineUser = new User(
                            entry.username,
                            UUID.nameUUIDFromBytes(("OfflinePlayer:" + entry.username).getBytes()),
                            "",
                            Optional.empty(),
                            Optional.empty()
                    );
                    //?} else {
                    /*User offlineUser = new User(
                            entry.username,
                            UUID.nameUUIDFromBytes(("OfflinePlayer:" + entry.username).getBytes()),
                            "",
                            Optional.empty(),
                            Optional.empty(),
                            User.Type.LEGACY
                    );*/
                    //?}
                    accessor.archivist$setUser(offlineUser);
                    accessor.archivist$setUserApiService(UserApiService.OFFLINE);
                    statusCallback.accept("Switched to offline: " + entry.username);
                }
                case MICROSOFT, TOKEN -> {
                    String accessToken = SecureStorage.decrypt(entry.accessTokenEncoded);
                    if (accessToken == null || accessToken.isEmpty()) {
                        statusCallback.accept("No access token — re-login required");
                        return;
                    }

                    // Check if token needs refresh (Microsoft accounts only)
                    if (entry.type == AccountEntry.AccountType.MICROSOFT && needsRefresh(entry)) {
                        statusCallback.accept("Refreshing token...");
                        try {
                            refreshMicrosoftToken(entry, statusCallback);
                            accessToken = SecureStorage.decrypt(entry.accessTokenEncoded);
                            if (accessToken == null || accessToken.isEmpty()) {
                                statusCallback.accept("Token refresh produced empty token");
                                return;
                            }
                        } catch (Exception e) {
                            statusCallback.accept("Token refresh failed: " + e.getMessage());
                            return;
                        }
                    }

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(entry.uuid);
                    } catch (IllegalArgumentException e) {
                        uuid = UUID.nameUUIDFromBytes(entry.username.getBytes());
                    }

                    //? if >=1.21.9 {
                    User msUser = new User(
                            entry.username,
                            uuid,
                            accessToken,
                            Optional.empty(),
                            Optional.empty()
                    );
                    //?} else {
                    /*User msUser = new User(
                            entry.username,
                            uuid,
                            accessToken,
                            Optional.empty(),
                            Optional.empty(),
                            User.Type.MSA
                    );*/
                    //?}
                    accessor.archivist$setUser(msUser);

                    // Create new UserApiService with the token
                    try {
                        YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(Proxy.NO_PROXY);
                        UserApiService apiService = authService.createUserApiService(accessToken);
                        accessor.archivist$setUserApiService(apiService);
                    } catch (Exception e) {
                        ArchivistMod.LOGGER.warn("Failed to create UserApiService, using offline", e);
                        accessor.archivist$setUserApiService(UserApiService.OFFLINE);
                    }

                    statusCallback.accept("Switched to: " + entry.username);
                }
            }

            activeAccountId = accountId;
            save();
        } catch (Exception e) {
            statusCallback.accept("Switch failed: " + e.getMessage());
            ArchivistMod.LOGGER.error("Account switch failed", e);
        }
    }

    private boolean needsRefresh(AccountEntry entry) {
        return entry.type == AccountEntry.AccountType.MICROSOFT
                && entry.lastRefreshed > 0
                && (System.currentTimeMillis() - entry.lastRefreshed) > TOKEN_REFRESH_THRESHOLD;
    }

    /**
     * Refresh a Microsoft account's token using the stored refresh token.
     */
    private void refreshMicrosoftToken(AccountEntry entry, Consumer<String> statusCallback) throws Exception {
        String refreshToken = SecureStorage.decrypt(entry.refreshTokenEncoded);
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new RuntimeException("No refresh token available");
        }

        // Step 1: Refresh Microsoft token
        String body = "client_id=" + MS_CLIENT_ID
                + "&grant_type=refresh_token"
                + "&refresh_token=" + refreshToken
                + "&scope=" + MS_SCOPE.replace(" ", "%20");
        String response = httpPost(MS_TOKEN_URL, body, "application/x-www-form-urlencoded");
        JsonObject tokenJson = JsonParser.parseString(response).getAsJsonObject();

        if (!tokenJson.has("access_token")) {
            String error = tokenJson.has("error") ? tokenJson.get("error").getAsString() : "unknown";
            throw new RuntimeException("Refresh failed: " + error);
        }

        String msAccessToken = tokenJson.get("access_token").getAsString();
        String newRefreshToken = tokenJson.has("refresh_token")
                ? tokenJson.get("refresh_token").getAsString() : refreshToken;

        // Step 2: Xbox Live → XSTS → Minecraft
        statusCallback.accept("Refreshing Xbox Live...");
        String xblToken = authenticateXboxLive(msAccessToken);
        String[] xstsResult = authenticateXSTS(xblToken);
        String mcToken = authenticateMinecraft(xstsResult[1], xstsResult[0]);

        // Step 3: Update profile
        JsonObject profile = getMinecraftProfile(mcToken);
        if (!profile.has("name") || !profile.has("id")) {
            throw new RuntimeException("Invalid Minecraft profile response");
        }
        entry.username = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();
        if (uuid.length() == 32) {
            uuid = uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-"
                    + uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
        }
        entry.uuid = uuid;
        entry.accessTokenEncoded = SecureStorage.encrypt(mcToken);
        entry.refreshTokenEncoded = SecureStorage.encrypt(newRefreshToken);
        entry.lastRefreshed = System.currentTimeMillis();
        save();

        statusCallback.accept("Token refreshed for " + entry.username);
    }

    // ── Microsoft OAuth Device Code Flow ──

    public synchronized CompletableFuture<AccountEntry> startMicrosoftLogin(Consumer<String> statusCallback) {
        if (oauthInProgress) {
            return CompletableFuture.failedFuture(new IllegalStateException("OAuth already in progress"));
        }

        oauthInProgress = true;
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1: Request device code
                statusCallback.accept("Requesting device code...");
                String body = "client_id=" + MS_CLIENT_ID + "&scope=" + MS_SCOPE.replace(" ", "%20");
                String response = httpPost(MS_DEVICE_CODE_URL, body, "application/x-www-form-urlencoded");
                JsonObject dcResponse = JsonParser.parseString(response).getAsJsonObject();

                // Check for error response from Microsoft
                if (dcResponse.has("error")) {
                    String error = dcResponse.has("error_description")
                            ? dcResponse.get("error_description").getAsString()
                            : dcResponse.get("error").getAsString();
                    throw new RuntimeException("Microsoft auth error: " + error);
                }
                if (!dcResponse.has("device_code") || !dcResponse.has("user_code")) {
                    throw new RuntimeException("Unexpected response from Microsoft: " + response);
                }

                deviceCode = dcResponse.get("device_code").getAsString();
                userCode = dcResponse.get("user_code").getAsString();
                verificationUri = dcResponse.has("verification_uri")
                        ? dcResponse.get("verification_uri").getAsString()
                        : "https://microsoft.com/devicelogin";
                int expiresIn = dcResponse.has("expires_in") ? dcResponse.get("expires_in").getAsInt() : 900;
                int interval = dcResponse.has("interval") ? dcResponse.get("interval").getAsInt() : POLL_INTERVAL_SECONDS;

                statusCallback.accept("Go to: " + verificationUri);
                statusCallback.accept("Enter code: " + userCode);

                // Step 2: Poll for token
                String msAccessToken = null;
                String msRefreshToken = null;
                long deadline = System.currentTimeMillis() + (expiresIn * 1000L);

                while (System.currentTimeMillis() < deadline && oauthInProgress) {
                    Thread.sleep(interval * 1000L);
                    String pollBody = "client_id=" + MS_CLIENT_ID
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                            + "&device_code=" + deviceCode;
                    String pollResponse = httpPost(MS_TOKEN_URL, pollBody, "application/x-www-form-urlencoded");
                    JsonObject pollJson = JsonParser.parseString(pollResponse).getAsJsonObject();

                    if (pollJson.has("access_token")) {
                        msAccessToken = pollJson.get("access_token").getAsString();
                        msRefreshToken = pollJson.has("refresh_token") ? pollJson.get("refresh_token").getAsString() : "";
                        break;
                    }

                    if (pollJson.has("error")) {
                        String error = pollJson.get("error").getAsString();
                        if ("authorization_pending".equals(error)) {
                            statusCallback.accept("Waiting for authorization...");
                            continue;
                        }
                        if ("slow_down".equals(error)) {
                            interval += 5;
                            continue;
                        }
                        throw new RuntimeException("OAuth error: " + error);
                    }
                }

                if (msAccessToken == null) {
                    throw new RuntimeException("OAuth timed out or was cancelled");
                }

                // Step 3-6: Auth chain + profile
                statusCallback.accept("Authenticating with Xbox Live...");
                String xblToken = authenticateXboxLive(msAccessToken);
                statusCallback.accept("Getting XSTS token...");
                String[] xstsResult = authenticateXSTS(xblToken);
                statusCallback.accept("Authenticating with Minecraft...");
                String mcToken = authenticateMinecraft(xstsResult[1], xstsResult[0]);
                statusCallback.accept("Fetching profile...");
                JsonObject profile = getMinecraftProfile(mcToken);
                String username = profile.get("name").getAsString();
                String uuid = profile.get("id").getAsString();
                if (uuid.length() == 32) {
                    uuid = uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-"
                            + uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
                }

                AccountEntry entry = new AccountEntry(username, uuid, AccountEntry.AccountType.MICROSOFT);
                entry.accessTokenEncoded = SecureStorage.encrypt(mcToken);
                entry.refreshTokenEncoded = SecureStorage.encrypt(msRefreshToken);
                entry.lastRefreshed = System.currentTimeMillis();

                accounts.add(entry);
                activeAccountId = entry.id;
                save();

                statusCallback.accept("Logged in as " + username);
                return entry;
            } catch (Exception e) {
                statusCallback.accept("Login failed: " + e.getMessage());
                throw new RuntimeException(e);
            } finally {
                oauthInProgress = false;
                deviceCode = null;
                userCode = null;
                verificationUri = null;
            }
        }, ArchivistExecutor::execute);
    }

    public void cancelOAuth() {
        oauthInProgress = false;
    }

    // ── Auth chain helpers ──

    private String authenticateXboxLive(String msAccessToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msAccessToken);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");
        String response = httpPost(XBL_AUTH_URL, GSON.toJson(body), "application/json");
        return JsonParser.parseString(response).getAsJsonObject().get("Token").getAsString();
    }

    private String[] authenticateXSTS(String xblToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        properties.add("UserTokens", tokens);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");
        String response = httpPost(XSTS_AUTH_URL, GSON.toJson(body), "application/json");
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        String token = json.get("Token").getAsString();
        String userHash = json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new String[]{token, userHash};
    }

    private String authenticateMinecraft(String userHash, String xstsToken) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        String response = httpPost(MC_AUTH_URL, GSON.toJson(body), "application/json");
        return JsonParser.parseString(response).getAsJsonObject().get("access_token").getAsString();
    }

    private JsonObject getMinecraftProfile(String mcToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcToken)
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String httpPost(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
