package com.koostack.cveil.core.platform.iam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.zip.CRC32;

/**
 * Generates personal access tokens with the {@code cveil_} prefix.
 *
 * <p>Token format: {@code cveil_<base62_random><crc32_base62>}
 *
 * <ul>
 *   <li>32 bytes of {@link SecureRandom} encoded as base62 (~43 characters)
 *   <li>CRC32 checksum of the random portion, encoded as base62 (~6 characters)
 * </ul>
 *
 * <p>The CRC32 suffix enables fast rejection of malformed tokens before hitting the database. The
 * full token is SHA-256 hashed for storage; the plaintext is shown to the user exactly once.
 */
public final class AccessTokenGenerator {

    private static final String PREFIX = "cveil_";
    private static final int RANDOM_BYTES = 32;
    private static final int PREFIX_DISPLAY_LENGTH = 8;

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private AccessTokenGenerator() {}

    /**
     * Generate a new access token.
     *
     * @return the generated token with plaintext, hash, and display prefix
     */
    public static GeneratedToken generate() {
        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[RANDOM_BYTES];
        random.nextBytes(randomBytes);

        String randomPart = encodeBase62(randomBytes);

        // CRC32 checksum of the random part for fast validation
        CRC32 crc = new CRC32();
        crc.update(randomPart.getBytes(StandardCharsets.UTF_8));
        String checksum = encodeBase62Long(crc.getValue());

        String plaintextToken = PREFIX + randomPart + checksum;
        String tokenHash = sha256Hex(plaintextToken);
        String tokenPrefix = PREFIX + randomPart.substring(0, PREFIX_DISPLAY_LENGTH);

        return new GeneratedToken(plaintextToken, tokenHash, tokenPrefix);
    }

    /**
     * Validate the CRC32 checksum of a token without hitting the database.
     *
     * @param token the full plaintext token
     * @return {@code true} if the checksum is valid
     */
    public static boolean validateChecksum(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return false;
        }

        String body = token.substring(PREFIX.length());
        // The random part is ~43 chars (32 bytes base62), checksum is ~6 chars
        // We need to find the split point: try decoding the last N chars as CRC32
        // The CRC32 base62 is at most 6 chars (max value 4294967295 → "4GFfc3")
        for (int checksumLen = 1; checksumLen <= 7 && checksumLen < body.length(); checksumLen++) {
            String randomPart = body.substring(0, body.length() - checksumLen);
            String checksumPart = body.substring(body.length() - checksumLen);

            CRC32 crc = new CRC32();
            crc.update(randomPart.getBytes(StandardCharsets.UTF_8));
            String expected = encodeBase62Long(crc.getValue());

            if (expected.equals(checksumPart)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a token string has the {@code cveil_} prefix.
     *
     * @param token the bearer token
     * @return {@code true} if the token starts with {@code cveil_}
     */
    public static boolean isAccessToken(String token) {
        return token != null && token.startsWith(PREFIX);
    }

    /**
     * Compute the SHA-256 hex digest of a token for database lookup.
     *
     * @param token the plaintext token
     * @return lowercase hex-encoded SHA-256 digest
     */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // -- Base62 encoding ------------------------------------------------------

    static String encodeBase62(byte[] data) {
        // Convert byte array to a positive BigInteger, then repeatedly mod 62
        java.math.BigInteger value = new java.math.BigInteger(1, data);
        java.math.BigInteger base = java.math.BigInteger.valueOf(62);

        if (value.equals(java.math.BigInteger.ZERO)) {
            return String.valueOf(BASE62[0]);
        }

        StringBuilder sb = new StringBuilder();
        while (value.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger[] divRem = value.divideAndRemainder(base);
            sb.append(BASE62[divRem[1].intValue()]);
            value = divRem[0];
        }
        return sb.reverse().toString();
    }

    static String encodeBase62Long(long value) {
        if (value == 0) {
            return String.valueOf(BASE62[0]);
        }
        // Work with unsigned value
        long unsigned = value & 0xFFFFFFFFL;
        StringBuilder sb = new StringBuilder();
        while (unsigned > 0) {
            sb.append(BASE62[(int) (unsigned % 62)]);
            unsigned /= 62;
        }
        return sb.reverse().toString();
    }

    /**
     * Result of token generation.
     *
     * @param plaintextToken the full token to show to the user (once only)
     * @param tokenHash the SHA-256 hex digest for database storage
     * @param tokenPrefix the display prefix (e.g. {@code cveil_a8Kx3mNp}) for logs and UI
     */
    public record GeneratedToken(String plaintextToken, String tokenHash, String tokenPrefix) {}
}
