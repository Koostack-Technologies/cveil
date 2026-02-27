package com.koostack.operio.plugin.platform.jooq.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-level field encryption using AES-256-GCM.
 *
 * <p>Provides transparent encrypt/decrypt for sensitive database columns
 * (e.g. {@code redis_acl_password} in the {@code tenant} table). Each
 * encryption produces a unique ciphertext thanks to a random 12-byte IV.
 *
 * <h2>Ciphertext format</h2>
 * <pre>
 *   Base64( IV[12] ‖ ciphertext ‖ GCM_tag[16] )
 * </pre>
 *
 * <h2>Key derivation</h2>
 * <p>The raw key string (from the {@code OPERIO_DB_ENCRYPTION_KEY} environment
 * variable) is hashed with SHA-256 to produce a 256-bit AES key. This means
 * any non-empty string is a valid key — no length or encoding requirements.
 *
 * <h2>Thread safety</h2>
 * <p>All methods are stateless and thread-safe. The {@link SecureRandom}
 * instance is thread-safe.
 *
 * <h2>Null handling</h2>
 * <p>Both {@link #encrypt} and {@link #decrypt} return {@code null} when the
 * input is {@code null} or blank, making them safe to use inline with
 * nullable database columns.
 *
 * @see javax.crypto.Cipher
 */
public final class FieldEncryptor {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits — NIST recommended
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private FieldEncryptor() {
        // Utility class — no instantiation
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Encrypts a plaintext string using AES-256-GCM.
     *
     * @param plaintext the value to encrypt (may be {@code null})
     * @param key       the encryption key string (hashed to 256 bits via SHA-256)
     * @return Base64-encoded ciphertext, or {@code null} if {@code plaintext}
     *         is {@code null} or blank
     * @throws IllegalArgumentException if {@code key} is {@code null} or blank
     * @throws IllegalStateException    if encryption fails (JCE misconfiguration)
     */
    public static String encrypt(String plaintext, String key) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext; // Preserve null/blank passthrough
        }
        requireNonBlankKey(key);

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKey(key),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );

            byte[] ciphertext = cipher.doFinal(
                plaintext.getBytes(StandardCharsets.UTF_8)
            );

            // Combine IV + ciphertext (includes GCM tag appended by JCE)
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException(
                "AES-256-GCM encryption failed. Ensure the JCE unlimited " +
                    "strength policy is installed (default since JDK 9+).",
                e
            );
        }
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM ciphertext.
     *
     * @param ciphertext the Base64-encoded ciphertext (may be {@code null})
     * @param key        the encryption key string (same key used for encryption)
     * @return the decrypted plaintext, or {@code null} if {@code ciphertext}
     *         is {@code null} or blank
     * @throws IllegalArgumentException if {@code key} is {@code null} or blank
     * @throws IllegalStateException    if decryption fails (wrong key, corrupted
     *                                  data, or JCE misconfiguration)
     */
    public static String decrypt(String ciphertext, String key) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext; // Preserve null/blank passthrough
        }
        requireNonBlankKey(key);

        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalStateException(
                    "Ciphertext too short — expected at least " +
                        GCM_IV_LENGTH +
                        " bytes for IV, got " +
                        combined.length
                );
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(
                combined,
                GCM_IV_LENGTH,
                encrypted,
                0,
                encrypted.length
            );

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(key),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e; // Re-throw our own exceptions
        } catch (Exception e) {
            throw new IllegalStateException(
                "AES-256-GCM decryption failed — wrong key or corrupted ciphertext.",
                e
            );
        }
    }

    /**
     * Attempts to decrypt a value, falling back to the raw value if
     * decryption fails.
     *
     * <p>This is designed for the rolling migration scenario where some rows
     * still contain legacy plaintext passwords and others have been encrypted.
     * If the value is not valid Base64 or doesn't decrypt correctly, it is
     * returned as-is with no exception.
     *
     * @param value the value to attempt decryption on (may be {@code null})
     * @param key   the encryption key string
     * @return the decrypted value, or the original value if decryption fails,
     *         or {@code null} if the input is {@code null}
     */
    public static String decryptOrPassthrough(String value, String key) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (key == null || key.isBlank()) {
            return value; // No key configured — return as-is
        }
        try {
            return decrypt(value, key);
        } catch (Exception e) {
            // Value is likely a legacy plaintext password — return as-is
            return value;
        }
    }

    /**
     * Encrypts a value only if an encryption key is provided. When the key
     * is {@code null} or blank, the plaintext is returned unchanged.
     *
     * <p>This supports backward compatibility for test and embedded
     * deployments that don't configure an encryption key.
     *
     * @param plaintext the value to encrypt (may be {@code null})
     * @param key       the encryption key, or {@code null} to skip encryption
     * @return the encrypted value, or the original plaintext if no key
     */
    public static String encryptIfKeyPresent(String plaintext, String key) {
        if (key == null || key.isBlank()) {
            return plaintext;
        }
        return encrypt(plaintext, key);
    }

    /**
     * Decrypts a value only if an encryption key is provided. When the key
     * is {@code null} or blank, the ciphertext is returned unchanged (assumed
     * to be plaintext).
     *
     * <p>When a key is provided but decryption fails (e.g. legacy plaintext
     * row), the original value is returned as a passthrough.
     *
     * @param ciphertext the value to decrypt (may be {@code null})
     * @param key        the encryption key, or {@code null} to skip decryption
     * @return the decrypted value, or the original value if no key or
     *         decryption fails
     */
    public static String decryptIfKeyPresent(String ciphertext, String key) {
        if (key == null || key.isBlank()) {
            return ciphertext;
        }
        return decryptOrPassthrough(ciphertext, key);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Derives a 256-bit AES key from the given key string using SHA-256.
     */
    private static SecretKeySpec deriveKey(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on all JVMs
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static void requireNonBlankKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                "Encryption key must not be null or blank. " +
                    "Set the OPERIO_DB_ENCRYPTION_KEY environment variable."
            );
        }
    }
}
