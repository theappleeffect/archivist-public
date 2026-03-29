package com.archivist.account;

import com.archivist.ArchivistMod;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for storing tokens.
 * Key is stored as 32 raw bytes in .minecraft/archivist/.keystore
 */
public final class SecureStorage {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final Path KEYSTORE_PATH = FabricLoader.getInstance().getGameDir()
            .resolve("archivist").resolve(".keystore");

    private static SecretKey cachedKey;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecureStorage() {}

    /**
     * Encrypt a plaintext string. Returns Base64(IV + ciphertext).
     */
    public static String encrypt(String plaintext) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            ArchivistMod.LOGGER.error("Encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypt a Base64(IV + ciphertext) string back to plaintext.
     */
    public static String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            SecretKey key = getOrCreateKey();
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < IV_LENGTH) return null;

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArchivistMod.LOGGER.error("Decryption failed", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        if (cachedKey != null) return cachedKey;

        if (Files.exists(KEYSTORE_PATH)) {
            byte[] keyBytes = Files.readAllBytes(KEYSTORE_PATH);
            if (keyBytes.length == 32) {
                cachedKey = new SecretKeySpec(keyBytes, "AES");
                return cachedKey;
            }
        }

        // Generate new key
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256, new SecureRandom());
        cachedKey = gen.generateKey();

        Path parent = KEYSTORE_PATH.getParent();
        if (!Files.exists(parent)) Files.createDirectories(parent);
        Files.write(KEYSTORE_PATH, cachedKey.getEncoded());

        return cachedKey;
    }
}
