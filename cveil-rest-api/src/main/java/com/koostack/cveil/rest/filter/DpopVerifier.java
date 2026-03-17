package com.koostack.cveil.rest.filter;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates DPoP (Demonstrating Proof-of-Possession) proof JWTs per RFC 9449.
 *
 * <p>This verifier checks:
 *
 * <ol>
 *   <li><strong>Proof structure</strong> — {@code typ: dpop+jwt}, asymmetric algorithm, embedded
 *       {@code jwk} in the header.
 *   <li><strong>Signature</strong> — the proof is signed by the embedded public key.
 *   <li><strong>Claims</strong> — {@code htm} (HTTP method), {@code htu} (URL), {@code iat}
 *       (issued-at within ±5 min), {@code jti} (unique, replay-protected).
 *   <li><strong>Token binding</strong> — if the access token has a {@code cnf.jkt} claim, the JWK
 *       SHA-256 thumbprint must match.
 *   <li><strong>Access token hash</strong> — if {@code ath} is present, it must equal the SHA-256
 *       of the access token.
 * </ol>
 *
 * <p>Thread-safe. A single instance should be shared across all request threads.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc9449">RFC 9449 — OAuth 2.0 DPoP</a>
 */
public class DpopVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(DpopVerifier.class);

    /** Maximum clock skew allowed for {@code iat} validation (seconds). */
    private static final long MAX_AGE_SECONDS = 300; // 5 minutes

    /**
     * Simple replay cache: jti → expiry timestamp. Entries older than MAX_AGE_SECONDS are lazily
     * evicted. In a clustered deployment this should be backed by a shared cache (Redis).
     */
    private final ConcurrentHashMap<String, Long> jtiCache = new ConcurrentHashMap<>();

    /** Counter for periodic eviction of expired jti entries. */
    private long evictionCounter = 0;

    /**
     * Verify a DPoP proof JWT against the current request context.
     *
     * @param dpopHeader the raw DPoP header value (compact-serialised JWT)
     * @param httpMethod the HTTP method of the request (e.g. "GET", "POST")
     * @param requestUrl the full request URL (scheme + authority + path, no query)
     * @param accessToken the access token from the Authorization header (for ath verification)
     * @param expectedJkt the expected JWK thumbprint from the access token's {@code cnf.jkt} claim,
     *     or {@code null} if not present
     * @return the computed JWK SHA-256 thumbprint of the proof's public key
     * @throws DpopVerificationException if the proof is invalid
     */
    public String verify(
            String dpopHeader,
            String httpMethod,
            String requestUrl,
            String accessToken,
            String expectedJkt) {

        if (dpopHeader == null || dpopHeader.isBlank()) {
            throw new DpopVerificationException("Missing DPoP proof header");
        }

        SignedJWT proofJwt;
        try {
            proofJwt = SignedJWT.parse(dpopHeader);
        } catch (ParseException e) {
            throw new DpopVerificationException("Malformed DPoP proof: " + e.getMessage());
        }

        // 1. Validate header
        var header = proofJwt.getHeader();

        String typ = header.getType() != null ? header.getType().toString() : null;
        if (!"dpop+jwt".equalsIgnoreCase(typ)) {
            throw new DpopVerificationException("DPoP proof typ must be 'dpop+jwt', got: " + typ);
        }

        JWSAlgorithm alg = header.getAlgorithm();
        if (alg == null || alg.getName().startsWith("HS")) {
            throw new DpopVerificationException(
                    "DPoP proof must use an asymmetric algorithm, got: " + alg);
        }

        Map<String, Object> jwkMap =
                header.getJWK() != null ? header.getJWK().toJSONObject() : null;
        if (jwkMap == null) {
            throw new DpopVerificationException("DPoP proof must contain a 'jwk' header parameter");
        }

        String jti = null;
        try {
            JWTClaimsSet earlyPeek = proofJwt.getJWTClaimsSet();
            jti = earlyPeek.getJWTID();
        } catch (ParseException ignored) {
            // Will be caught properly during full claims validation below
        }
        if (jti != null && jtiCache.containsKey(jti)) {
            throw new DpopVerificationException("DPoP proof replay detected (duplicate jti)");
        }

        // 2. Parse the embedded public key and verify signature
        JWK jwk;
        try {
            jwk = JWK.parse(jwkMap);
        } catch (ParseException e) {
            throw new DpopVerificationException("Invalid JWK in DPoP proof: " + e.getMessage());
        }

        if (jwk.isPrivate()) {
            throw new DpopVerificationException("DPoP proof JWK must not contain a private key");
        }

        try {
            boolean valid;
            if (jwk instanceof ECKey ecKey) {
                valid = proofJwt.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(ecKey));
            } else {
                // RSA fallback
                var rsaKey = jwk.toRSAKey();
                valid = proofJwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(rsaKey));
            }
            if (!valid) {
                throw new DpopVerificationException("DPoP proof signature verification failed");
            }
        } catch (JOSEException e) {
            throw new DpopVerificationException(
                    "DPoP proof signature verification error: " + e.getMessage());
        }

        // 3. Validate claims
        JWTClaimsSet claims;
        try {
            claims = proofJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DpopVerificationException("Failed to parse DPoP claims: " + e.getMessage());
        }

        String htm = (String) claims.getClaim("htm");
        if (htm == null || !htm.equalsIgnoreCase(httpMethod)) {
            throw new DpopVerificationException(
                    "DPoP htm mismatch: expected " + httpMethod + ", got " + htm);
        }

        String htu = (String) claims.getClaim("htu");
        if (htu == null || !htu.equals(requestUrl)) {
            throw new DpopVerificationException(
                    "DPoP htu mismatch: expected " + requestUrl + ", got " + htu);
        }

        var iat = claims.getIssueTime();
        if (iat == null) {
            throw new DpopVerificationException("DPoP proof missing 'iat' claim");
        }
        long now = Instant.now().getEpochSecond();
        long iatEpoch = iat.toInstant().getEpochSecond();
        if (Math.abs(now - iatEpoch) > MAX_AGE_SECONDS) {
            throw new DpopVerificationException(
                    "DPoP proof too old or too far in the future (iat="
                            + iatEpoch
                            + ", now="
                            + now
                            + ")");
        }

        // Re-validate jti from the verified claims (we peeked at it early for DoS protection)
        jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new DpopVerificationException("DPoP proof missing 'jti' claim");
        }

        // Replay protection (definitive check — caches the jti)
        Long prev = jtiCache.putIfAbsent(jti, now + MAX_AGE_SECONDS);
        if (prev != null) {
            throw new DpopVerificationException("DPoP proof replay detected (duplicate jti)");
        }
        evictStaleJti();

        // 4. Compute JWK thumbprint and verify token binding
        String thumbprint;
        try {
            Base64URL tp = jwk.computeThumbprint("SHA-256");
            thumbprint = tp.toString();
        } catch (JOSEException e) {
            throw new DpopVerificationException(
                    "Failed to compute JWK thumbprint: " + e.getMessage());
        }

        if (expectedJkt != null && !expectedJkt.equals(thumbprint)) {
            throw new DpopVerificationException("DPoP JWK thumbprint does not match token cnf.jkt");
        }

        // 5. Verify access token hash (ath) if present
        String ath = (String) claims.getClaim("ath");
        if (ath != null && accessToken != null) {
            String expectedAth = sha256Base64Url(accessToken);
            if (!ath.equals(expectedAth)) {
                throw new DpopVerificationException("DPoP ath (access token hash) mismatch");
            }
        }

        LOG.debug(
                "DPoP proof verified: jti={}, htm={}, thumbprint={}…",
                jti,
                htm,
                thumbprint.substring(0, Math.min(8, thumbprint.length())));
        return thumbprint;
    }

    /** Periodically remove expired jti entries to prevent unbounded growth. */
    private void evictStaleJti() {
        if (++evictionCounter % 100 == 0) {
            long now = Instant.now().getEpochSecond();
            jtiCache.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    /** SHA-256 hash of the input, base64url-encoded (no padding). */
    static String sha256Base64Url(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64URL.encode(hash).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Exception thrown when DPoP proof verification fails. */
    public static class DpopVerificationException extends RuntimeException {
        public DpopVerificationException(String message) {
            super(message);
        }
    }
}
