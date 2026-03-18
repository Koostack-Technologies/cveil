package com.koostack.cveil.plugin.platform.keycloak;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.IdentityContext;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWKS-based JWT verifier that caches key sets per issuer URL using Caffeine.
 *
 * <p>This verifier is generic — it works with any OIDC-compliant IdP (Keycloak, Auth0, Okta, Azure
 * AD) by fetching their JWKS endpoint.
 *
 * <h2>Caching</h2>
 *
 * <p>JWKS key sets are cached per issuer URL using a Caffeine cache with a configurable TTL and a
 * maximum of 100 entries. On a {@code kid} miss (e.g. after key rotation), the cache is refreshed
 * and verification retried once.
 *
 * <h2>Claim Validation (Defense-in-Depth)</h2>
 *
 * <p>In addition to cryptographic JWKS signature verification, this verifier optionally validates:
 *
 * <ul>
 *   <li><strong>{@code iss} (issuer)</strong> — must match a configurable regex pattern (e.g.
 *       {@code https://keycloak\.example\.com/realms/.+})
 *   <li><strong>{@code aud} (audience)</strong> — must contain the expected audience value (e.g.
 *       the Keycloak client ID {@code cveil-backend})
 * </ul>
 *
 * <p>These checks prevent token confusion attacks where a valid token from an unrelated issuer or
 * intended for a different audience could be accepted.
 */
public class JwksTokenVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(JwksTokenVerifier.class);

    private static final long DEFAULT_CACHE_TTL_MS = 300_000; // 5 minutes
    private static final int MAX_CACHE_ENTRIES = 100;

    private final Cache<String, JWKSet> jwksCache;

    /** Optional compiled regex for validating the {@code iss} claim. Null to skip validation. */
    private final Pattern expectedIssuerPattern;

    /** Optional audience value that must appear in the {@code aud} claim. Null to skip. */
    private final String expectedAudience;

    public JwksTokenVerifier() {
        this(DEFAULT_CACHE_TTL_MS, null, null);
    }

    public JwksTokenVerifier(long cacheTtlMs) {
        this(cacheTtlMs, null, null);
    }

    /**
     * Full constructor with claim validation support.
     *
     * @param cacheTtlMs JWKS cache TTL in milliseconds
     * @param expectedIssuerPattern regex for validating the {@code iss} claim, or null to skip
     * @param expectedAudience audience value that must appear in {@code aud}, or null to skip
     */
    public JwksTokenVerifier(
            long cacheTtlMs, String expectedIssuerPattern, String expectedAudience) {
        this.jwksCache =
                Caffeine.newBuilder()
                        .maximumSize(MAX_CACHE_ENTRIES)
                        .expireAfterWrite(Duration.ofMillis(cacheTtlMs))
                        .build();
        this.expectedIssuerPattern =
                expectedIssuerPattern != null ? Pattern.compile(expectedIssuerPattern) : null;
        this.expectedAudience = expectedAudience;
    }

    /**
     * Verify a JWT against the JWKS at the given endpoint URL.
     *
     * @param jwksUrl the JWKS endpoint URL (e.g. {@code
     *     http://keycloak:8180/realms/tenant-1/protocol/openid-connect/certs})
     * @param token the raw JWT string
     * @param tenantId expected tenant ID (used to build the IdentityContext)
     * @return the verified identity context
     * @throws CveilAuthException if verification fails
     */
    public IdentityContext verify(String jwksUrl, String token, String tenantId) {
        try {
            JWTClaimsSet claims = verifyAndParseClaims(jwksUrl, token);
            validateClaims(claims);
            return extractIdentity(claims, token, tenantId);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            // Include JWT header in error for diagnosing algorithm / key mismatches
            String headerInfo = "";
            try {
                SignedJWT jwt = SignedJWT.parse(token);
                JWSHeader hdr = jwt.getHeader();
                headerInfo =
                        " (JWT alg="
                                + hdr.getAlgorithm()
                                + ", kid="
                                + hdr.getKeyID()
                                + ", jwksUrl="
                                + jwksUrl
                                + ")";
            } catch (Exception ignored) {
            }
            throw new CveilAuthException("JWT verification failed: " + e.getMessage() + headerInfo);
        }
    }

    private JWTClaimsSet verifyAndParseClaims(String jwksUrl, String token)
            throws BadJOSEException, JOSEException, ParseException {
        // Log JWT header for diagnostics (kid + alg)
        try {
            SignedJWT preParseJwt = SignedJWT.parse(token);
            JWSHeader hdr = preParseJwt.getHeader();
            LOG.debug(
                    "Verifying JWT: alg={}, kid={}, jwksUrl={}",
                    hdr.getAlgorithm(),
                    hdr.getKeyID(),
                    jwksUrl);
        } catch (ParseException pe) {
            LOG.warn("Failed to pre-parse JWT header for diagnostics: {}", pe.getMessage());
        }

        JWKSet jwkSet = getJwks(jwksUrl, false);
        LOG.debug("JWKS loaded: {} keys (url={})", jwkSet.getKeys().size(), jwksUrl);
        if (jwkSet.getKeys().isEmpty()) {
            LOG.warn("JWKS from {} contains zero keys — forcing refresh", jwksUrl);
            jwkSet = getJwks(jwksUrl, true);
            LOG.debug("JWKS after refresh: {} keys", jwkSet.getKeys().size());
        }
        try {
            return processToken(jwkSet, token);
        } catch (BadJOSEException e) {
            // Key rotation: retry with a fresh JWKS fetch
            LOG.warn(
                    "JWT verification failed (alg/kid mismatch?), retrying with refreshed JWKS."
                            + " Error: {}. JWKS keys: {}",
                    e.getMessage(),
                    jwkSet.getKeys().stream()
                            .map(
                                    k ->
                                            "kid="
                                                    + k.getKeyID()
                                                    + " alg="
                                                    + k.getAlgorithm()
                                                    + " use="
                                                    + k.getKeyUse())
                            .toList());
            jwkSet = getJwks(jwksUrl, true);
            LOG.debug(
                    "JWKS after forced refresh: {} keys: {}",
                    jwkSet.getKeys().size(),
                    jwkSet.getKeys().stream()
                            .map(
                                    k ->
                                            "kid="
                                                    + k.getKeyID()
                                                    + " alg="
                                                    + k.getAlgorithm()
                                                    + " use="
                                                    + k.getKeyUse())
                            .toList());
            return processToken(jwkSet, token);
        }
    }

    /**
     * Validates the {@code iss} and {@code aud} claims against configured expectations.
     *
     * <p>These checks are defense-in-depth — the JWKS signature already constrains the token to a
     * specific signing key. Claim validation adds protection against token confusion where
     * different services share the same IdP but should not accept each other's tokens.
     */
    private void validateClaims(JWTClaimsSet claims) {
        // Validate issuer
        if (expectedIssuerPattern != null) {
            String issuer = claims.getIssuer();
            if (issuer == null || !expectedIssuerPattern.matcher(issuer).matches()) {
                LOG.warn(
                        "JWT issuer mismatch: got '{}', expected pattern '{}'",
                        issuer,
                        expectedIssuerPattern.pattern());
                throw new CveilAuthException("JWT issuer does not match expected pattern");
            }
        }

        // Validate audience
        if (expectedAudience != null) {
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                LOG.warn(
                        "JWT audience mismatch: got '{}', expected '{}'",
                        audience,
                        expectedAudience);
                throw new CveilAuthException("JWT audience does not contain expected value");
            }
        }
    }

    private JWTClaimsSet processToken(JWKSet jwkSet, String token)
            throws BadJOSEException, JOSEException, ParseException {

        // Read the signing algorithm from the JWT header rather than hardcoding RS256.
        // Keycloak 26+ may use RS256, PS256, ES256 or EdDSA depending on realm key config.
        SignedJWT signedJwt = SignedJWT.parse(token);
        JWSHeader header = signedJwt.getHeader();
        JWSAlgorithm alg = header.getAlgorithm();
        if (alg == null) {
            throw new BadJOSEException("JWT header missing 'alg' claim");
        }

        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(jwkSet);
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(alg, keySource);

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        return processor.process(signedJwt, null);
    }

    @SuppressWarnings("unchecked")
    private IdentityContext extractIdentity(JWTClaimsSet claims, String token, String tenantId) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new CveilAuthException("JWT missing 'sub' claim");
        }

        String email = (String) claims.getClaim("email");

        // Extract roles from Keycloak's realm_access.roles structure
        Set<String> roles = new HashSet<>();
        Object realmAccess = claims.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> ra) {
            Object rolesObj = ra.get("roles");
            if (rolesObj instanceof List<?> roleList) {
                for (Object r : roleList) {
                    if (r instanceof String s) {
                        roles.add(s);
                    }
                }
            }
        }

        // Also check standard OIDC 'roles' claim (for non-Keycloak providers)
        Object directRoles = claims.getClaim("roles");
        if (directRoles instanceof List<?> roleList) {
            for (Object r : roleList) {
                if (r instanceof String s) {
                    roles.add(s);
                }
            }
        }

        String issuer = claims.getIssuer();
        String provider = issuer != null && issuer.contains("/realms/") ? "keycloak" : "oidc";

        Map<String, Object> claimsMap = claims.getClaims();

        return new IdentityContext(
                subject,
                token,
                email,
                Collections.unmodifiableSet(roles),
                claimsMap != null ? Map.copyOf(claimsMap) : Map.of(),
                tenantId,
                provider);
    }

    private JWKSet getJwks(String jwksUrl, boolean forceRefresh) {
        if (!forceRefresh) {
            JWKSet cached = jwksCache.getIfPresent(jwksUrl);
            if (cached != null) {
                LOG.debug("Using cached JWKS for {} ({} keys)", jwksUrl, cached.getKeys().size());
                return cached;
            }
        }

        try {
            LOG.debug("Fetching JWKS from: {} (forceRefresh={})", jwksUrl, forceRefresh);
            JWKSet jwkSet = JWKSet.load(URI.create(jwksUrl).toURL());
            if (jwkSet.getKeys().isEmpty()) {
                LOG.warn(
                        "JWKS from {} returned 0 keys — realm may not have signing keys yet",
                        jwksUrl);
            } else {
                LOG.debug(
                        "Fetched {} keys from {}: {}",
                        jwkSet.getKeys().size(),
                        jwksUrl,
                        jwkSet.getKeys().stream()
                                .map(
                                        k ->
                                                "kid="
                                                        + k.getKeyID()
                                                        + " kty="
                                                        + k.getKeyType()
                                                        + " alg="
                                                        + k.getAlgorithm()
                                                        + " use="
                                                        + k.getKeyUse())
                                .toList());
            }
            jwksCache.put(jwksUrl, jwkSet);
            return jwkSet;
        } catch (IOException | ParseException e) {
            LOG.warn("Failed to fetch JWKS from {}: {}", jwksUrl, e.getMessage());
            // If we have a stale cache entry, use it rather than failing
            JWKSet stale = jwksCache.getIfPresent(jwksUrl);
            if (stale != null) {
                LOG.warn(
                        "Using stale JWKS cache for {} ({} keys)", jwksUrl, stale.getKeys().size());
                return stale;
            }
            throw new CveilAuthException(
                    "Failed to fetch JWKS from " + jwksUrl + ": " + e.getMessage());
        }
    }

    /** Evict cached JWKS for a specific URL (e.g. on realm deletion). */
    public void evict(String jwksUrl) {
        jwksCache.invalidate(jwksUrl);
    }

    /** Clear all cached JWKS. */
    public void clearCache() {
        jwksCache.invalidateAll();
    }
}
