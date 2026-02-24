package com.koostack.operio.plugin.platform.jooq.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FieldEncryptor} — AES-256-GCM field-level encryption.
 */
class FieldEncryptorTest {

    private static final String KEY = "my-super-secret-encryption-key-2026";
    private static final String OTHER_KEY = "a-different-secret-key-entirely";

    // -----------------------------------------------------------------------
    // Round-trip encrypt → decrypt
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Round-trip encrypt/decrypt")
    class RoundTripTests {

        @Test
        @DisplayName("encrypt then decrypt returns original plaintext")
        void encryptThenDecryptReturnsOriginal() {
            String plaintext = "s0me-R4nd0m_P@ssw0rd!";
            String ciphertext = FieldEncryptor.encrypt(plaintext, KEY);
            String decrypted = FieldEncryptor.decrypt(ciphertext, KEY);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("round-trip works with empty string")
        void roundTripEmptyString() {
            // Empty strings are treated as blank → passthrough
            String result = FieldEncryptor.encrypt("", KEY);
            assertEquals("", result);
        }

        @Test
        @DisplayName("round-trip works with long plaintext")
        void roundTripLongPlaintext() {
            String plaintext = "a".repeat(1000);
            String ciphertext = FieldEncryptor.encrypt(plaintext, KEY);
            String decrypted = FieldEncryptor.decrypt(ciphertext, KEY);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("round-trip works with Unicode content")
        void roundTripUnicode() {
            String plaintext = "пароль-密码-パスワード-🔐";
            String ciphertext = FieldEncryptor.encrypt(plaintext, KEY);
            String decrypted = FieldEncryptor.decrypt(ciphertext, KEY);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("round-trip works with typical Redis ACL password")
        void roundTripTypicalPassword() {
            // 32-char base64url password (what generateSecurePassword() produces)
            String password = "dGhpcyBpcyBhIHRlc3QgcGFzc3dvcmQ";
            String ciphertext = FieldEncryptor.encrypt(password, KEY);
            assertNotEquals(password, ciphertext,
                "Ciphertext should differ from plaintext");
            String decrypted = FieldEncryptor.decrypt(ciphertext, KEY);
            assertEquals(password, decrypted);
        }
    }

    // -----------------------------------------------------------------------
    // IV randomness (each encryption should produce different ciphertext)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("IV randomness")
    class IvRandomnessTests {

        @Test
        @DisplayName("two encryptions of the same plaintext produce different ciphertexts")
        void differentCiphertextsForSamePlaintext() {
            String plaintext = "same-password";
            String ciphertext1 = FieldEncryptor.encrypt(plaintext, KEY);
            String ciphertext2 = FieldEncryptor.encrypt(plaintext, KEY);
            assertNotEquals(ciphertext1, ciphertext2,
                "Each encryption should produce a unique ciphertext " +
                    "(different random IV)");

            // Both should still decrypt correctly
            assertEquals(plaintext, FieldEncryptor.decrypt(ciphertext1, KEY));
            assertEquals(plaintext, FieldEncryptor.decrypt(ciphertext2, KEY));
        }
    }

    // -----------------------------------------------------------------------
    // Null / blank handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null and blank handling")
    class NullBlankTests {

        @Test
        @DisplayName("encrypt(null, key) returns null")
        void encryptNullReturnsNull() {
            assertNull(FieldEncryptor.encrypt(null, KEY));
        }

        @Test
        @DisplayName("decrypt(null, key) returns null")
        void decryptNullReturnsNull() {
            assertNull(FieldEncryptor.decrypt(null, KEY));
        }

        @Test
        @DisplayName("encrypt(blank, key) returns blank")
        void encryptBlankReturnsBlank() {
            assertEquals("   ", FieldEncryptor.encrypt("   ", KEY));
        }

        @Test
        @DisplayName("decrypt(blank, key) returns blank")
        void decryptBlankReturnsBlank() {
            assertEquals("   ", FieldEncryptor.decrypt("   ", KEY));
        }

        @Test
        @DisplayName("encrypt(plaintext, null) throws IllegalArgumentException")
        void encryptNullKeyThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldEncryptor.encrypt("secret", null));
        }

        @Test
        @DisplayName("encrypt(plaintext, blank) throws IllegalArgumentException")
        void encryptBlankKeyThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldEncryptor.encrypt("secret", "  "));
        }

        @Test
        @DisplayName("decrypt(ciphertext, null) throws IllegalArgumentException")
        void decryptNullKeyThrows() {
            String ct = FieldEncryptor.encrypt("secret", KEY);
            assertThrows(IllegalArgumentException.class,
                () -> FieldEncryptor.decrypt(ct, null));
        }

        @Test
        @DisplayName("decrypt(ciphertext, blank) throws IllegalArgumentException")
        void decryptBlankKeyThrows() {
            String ct = FieldEncryptor.encrypt("secret", KEY);
            assertThrows(IllegalArgumentException.class,
                () -> FieldEncryptor.decrypt(ct, "  "));
        }
    }

    // -----------------------------------------------------------------------
    // Wrong key detection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Wrong key detection")
    class WrongKeyTests {

        @Test
        @DisplayName("decrypting with a different key throws IllegalStateException")
        void wrongKeyThrows() {
            String ciphertext = FieldEncryptor.encrypt("secret", KEY);
            assertThrows(IllegalStateException.class,
                () -> FieldEncryptor.decrypt(ciphertext, OTHER_KEY));
        }

        @Test
        @DisplayName("decrypting corrupted ciphertext throws IllegalStateException")
        void corruptedCiphertextThrows() {
            String ciphertext = FieldEncryptor.encrypt("secret", KEY);
            // Corrupt by flipping characters
            String corrupted = ciphertext.substring(0, 5) + "XXXX" +
                ciphertext.substring(9);
            assertThrows(IllegalStateException.class,
                () -> FieldEncryptor.decrypt(corrupted, KEY));
        }

        @Test
        @DisplayName("decrypting too-short ciphertext throws IllegalStateException")
        void tooShortCiphertextThrows() {
            // Less than 12 bytes (IV length) after Base64 decode
            assertThrows(IllegalStateException.class,
                () -> FieldEncryptor.decrypt("dG9vc2hvcnQ=", KEY));
        }
    }

    // -----------------------------------------------------------------------
    // Ciphertext format
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Ciphertext format")
    class CiphertextFormatTests {

        @Test
        @DisplayName("ciphertext is valid Base64")
        void ciphertextIsBase64() {
            String ciphertext = FieldEncryptor.encrypt("test-password", KEY);
            assertNotNull(ciphertext);
            assertDoesNotThrow(
                () -> java.util.Base64.getDecoder().decode(ciphertext),
                "Ciphertext should be valid Base64"
            );
        }

        @Test
        @DisplayName("ciphertext is longer than plaintext (IV + GCM tag overhead)")
        void ciphertextLongerThanPlaintext() {
            String plaintext = "short";
            String ciphertext = FieldEncryptor.encrypt(plaintext, KEY);
            // 12-byte IV + 5-byte plaintext + 16-byte GCM tag = 33 bytes
            // Base64(33) = 44 chars
            assertTrue(ciphertext.length() > plaintext.length(),
                "Ciphertext should be longer due to IV + GCM tag");
        }
    }

    // -----------------------------------------------------------------------
    // Conditional helpers (encryptIfKeyPresent / decryptIfKeyPresent)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Conditional encrypt/decrypt helpers")
    class ConditionalHelperTests {

        @Test
        @DisplayName("encryptIfKeyPresent with null key returns plaintext unchanged")
        void encryptIfKeyPresentNullKey() {
            String plaintext = "my-password";
            assertEquals(plaintext,
                FieldEncryptor.encryptIfKeyPresent(plaintext, null));
        }

        @Test
        @DisplayName("encryptIfKeyPresent with blank key returns plaintext unchanged")
        void encryptIfKeyPresentBlankKey() {
            String plaintext = "my-password";
            assertEquals(plaintext,
                FieldEncryptor.encryptIfKeyPresent(plaintext, "  "));
        }

        @Test
        @DisplayName("encryptIfKeyPresent with valid key encrypts")
        void encryptIfKeyPresentWithKey() {
            String plaintext = "my-password";
            String result = FieldEncryptor.encryptIfKeyPresent(plaintext, KEY);
            assertNotEquals(plaintext, result);
            assertEquals(plaintext, FieldEncryptor.decrypt(result, KEY));
        }

        @Test
        @DisplayName("decryptIfKeyPresent with null key returns value unchanged")
        void decryptIfKeyPresentNullKey() {
            String ciphertext = FieldEncryptor.encrypt("secret", KEY);
            assertEquals(ciphertext,
                FieldEncryptor.decryptIfKeyPresent(ciphertext, null));
        }

        @Test
        @DisplayName("decryptIfKeyPresent with blank key returns value unchanged")
        void decryptIfKeyPresentBlankKey() {
            String ciphertext = FieldEncryptor.encrypt("secret", KEY);
            assertEquals(ciphertext,
                FieldEncryptor.decryptIfKeyPresent(ciphertext, "  "));
        }

        @Test
        @DisplayName("decryptIfKeyPresent with valid key decrypts")
        void decryptIfKeyPresentWithKey() {
            String ciphertext = FieldEncryptor.encrypt("secret", KEY);
            assertEquals("secret",
                FieldEncryptor.decryptIfKeyPresent(ciphertext, KEY));
        }

        @Test
        @DisplayName("decryptIfKeyPresent with null value returns null")
        void decryptIfKeyPresentNullValue() {
            assertNull(FieldEncryptor.decryptIfKeyPresent(null, KEY));
        }
    }

    // -----------------------------------------------------------------------
    // decryptOrPassthrough (legacy plaintext support)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("decryptOrPassthrough (legacy plaintext)")
    class PassthroughTests {

        @Test
        @DisplayName("passes through legacy plaintext when decryption fails")
        void passthroughLegacyPlaintext() {
            // A value that is NOT valid AES-GCM ciphertext (legacy row)
            String legacyPlaintext = "dGhpcyBpcyBhIHRlc3Q";
            String result = FieldEncryptor.decryptOrPassthrough(
                legacyPlaintext, KEY);
            assertEquals(legacyPlaintext, result,
                "Legacy plaintext should pass through unchanged");
        }

        @Test
        @DisplayName("decrypts valid ciphertext successfully")
        void decryptsValidCiphertext() {
            String plaintext = "real-password";
            String ciphertext = FieldEncryptor.encrypt(plaintext, KEY);
            String result = FieldEncryptor.decryptOrPassthrough(ciphertext, KEY);
            assertEquals(plaintext, result);
        }

        @Test
        @DisplayName("null key returns value as-is")
        void nullKeyReturnsAsIs() {
            String value = "anything";
            assertEquals(value,
                FieldEncryptor.decryptOrPassthrough(value, null));
        }

        @Test
        @DisplayName("blank key returns value as-is")
        void blankKeyReturnsAsIs() {
            String value = "anything";
            assertEquals(value,
                FieldEncryptor.decryptOrPassthrough(value, "  "));
        }

        @Test
        @DisplayName("null value returns null")
        void nullValueReturnsNull() {
            assertNull(FieldEncryptor.decryptOrPassthrough(null, KEY));
        }

        @Test
        @DisplayName("blank value returns blank")
        void blankValueReturnsBlank() {
            assertEquals("  ",
                FieldEncryptor.decryptOrPassthrough("  ", KEY));
        }
    }

    // -----------------------------------------------------------------------
    // Key sensitivity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Key sensitivity")
    class KeySensitivityTests {

        @Test
        @DisplayName("keys differing by one character produce different ciphertext that won't cross-decrypt")
        void differentKeysProduceDifferentResults() {
            String plaintext = "test-password";
            String ct1 = FieldEncryptor.encrypt(plaintext, "key-A");
            String ct2 = FieldEncryptor.encrypt(plaintext, "key-B");

            // Different keys should not decrypt each other's ciphertext
            assertThrows(IllegalStateException.class,
                () -> FieldEncryptor.decrypt(ct1, "key-B"));
            assertThrows(IllegalStateException.class,
                () -> FieldEncryptor.decrypt(ct2, "key-A"));

            // But each decrypts with its own key
            assertEquals(plaintext, FieldEncryptor.decrypt(ct1, "key-A"));
            assertEquals(plaintext, FieldEncryptor.decrypt(ct2, "key-B"));
        }

        @Test
        @DisplayName("same key always decrypts correctly regardless of key string length")
        void anyKeyLengthWorks() {
            String plaintext = "password123";

            // Short key
            String ct1 = FieldEncryptor.encrypt(plaintext, "k");
            assertEquals(plaintext, FieldEncryptor.decrypt(ct1, "k"));

            // Long key
            String longKey = "a".repeat(500);
            String ct2 = FieldEncryptor.encrypt(plaintext, longKey);
            assertEquals(plaintext, FieldEncryptor.decrypt(ct2, longKey));
        }
    }
}
