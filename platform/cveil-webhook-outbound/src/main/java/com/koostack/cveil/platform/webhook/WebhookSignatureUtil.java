package com.koostack.cveil.platform.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signature utilities for outbound webhook payloads.
 *
 * <p>Signs the concatenation of {@code timestamp.payload} so receivers can reject replayed events
 * by verifying the timestamp is recent (recommended tolerance: 5 minutes).
 */
public final class WebhookSignatureUtil {

    private static final String ALGORITHM = "HmacSHA256";

    /** Maximum age (in seconds) a receiver should tolerate for a signed timestamp. */
    public static final long TOLERANCE_SECONDS = 300; // 5 minutes

    private WebhookSignatureUtil() {}

    /**
     * Computes an HMAC-SHA256 signature for {@code timestamp.payload}.
     *
     * @param payload the JSON body to sign
     * @param secret the endpoint's shared secret
     * @param timestampSeconds Unix epoch seconds of the signing instant
     * @return hex-encoded signature string
     */
    public static String sign(String payload, String secret, long timestampSeconds) {
        String signedContent = timestampSeconds + "." + payload;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Overload that uses the current system time as the timestamp.
     *
     * @return hex-encoded signature string
     */
    public static String sign(String payload, String secret) {
        return sign(payload, secret, java.time.Instant.now().getEpochSecond());
    }

    /**
     * Verifies that the given signature matches the payload + secret + timestamp combination and
     * that the timestamp is within the tolerance window.
     *
     * @return true if the signature is valid and the timestamp is recent
     */
    public static boolean verify(
            String payload, String secret, long timestampSeconds, String expectedSignature) {
        long now = java.time.Instant.now().getEpochSecond();
        if (Math.abs(now - timestampSeconds) > TOLERANCE_SECONDS) {
            return false; // stale or future timestamp — possible replay
        }
        String computed = sign(payload, secret, timestampSeconds);
        return constantTimeEquals(computed, expectedSignature);
    }

    /**
     * Legacy verify without timestamp — kept for backwards compatibility during migration.
     *
     * @return true if the signature is valid (no timestamp check)
     */
    public static boolean verify(String payload, String secret, String expectedSignature) {
        // Attempt verification with no timestamp context — callers should migrate
        // to the timestamp-aware overload.
        String computed = sign(payload, secret, 0);
        return constantTimeEquals(computed, expectedSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
