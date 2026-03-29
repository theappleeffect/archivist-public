package com.archivist.account;

import java.util.UUID;

/**
 * A single stored account profile.
 */
public final class AccountEntry {

    public enum AccountType { MICROSOFT, CRACKED, TOKEN }

    public String id = UUID.randomUUID().toString();
    public String username = "";
    public String uuid = "";
    public AccountType type = AccountType.MICROSOFT;
    public String accessTokenEncoded = "";
    public String refreshTokenEncoded = "";
    public long lastRefreshed = 0;
    public boolean isOriginal = false;

    public AccountEntry() {}

    public AccountEntry(String username, String uuid, AccountType type) {
        this.username = username;
        this.uuid = uuid;
        this.type = type;
    }
}
