package com.koostack.cveil.plugin.platform.keycloak;

import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.IdentityContext;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWKS-based JWT verifier that caches key sets per issuer URL.
 *
 * <p>This verifier is generic — it works with any OIDC-compliant IdP (Keycloak, Auth0, Okta, Azure
 * AD) by fetching their JWKS endpoint.
 *
 * <h2>Caching</h2>
 *
 * <p>JWKS key sets are cached per issuer with a configurable TTL. On a {@code kid} miss (e.g. after
 * key rotation), the cache is refreshed and verification retried once.
 */
public class JwksTokenVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(JwksTokenVerifier.class);

    private static final long DEFAULT_CACHE_TTL_MS = 300_000; // 5 minutes

    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    public JwksTokenVerifier() {
        this(DEFAULT_CACHE_TTL_MS);
    }

    public JwksTokenVerifier(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
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
            return extractIdentity(claims, token, tenantId);
        } catch (CveilAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilAuthException("JWT verification failed: " + e.getMessage());
        }
    }

    private JWTClaimsSet verifyAndParseClaims(String jwksUrl, String token)
            throws BadJOSEException, JOSEException, ParseException {

        JWKSet jwkSet = getJwks(jwksUrl, false);
        try {
            return processToken(jwkSet, token);
        } catch (BadJOSEException e) {
            // Key rotation: retry with a fresh JWKS fetch
            LOG.debug("JWT verification failed, retrying with refreshed JWKS: {}", e.getMessage());
            jwkSet = getJwks(jwksUrl, true);
            return processToken(jwkSet, token);
        }
    }

    private JWTClaimsSet processToken(JWKSet jwkSet, String token)
            throws BadJOSEException, JOSEException, ParseException {

        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(jwkSet);
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        return processor.process(token, null);
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
        CachedJwks cached = jwksCache.get(jwksUrl);

        if (!forceRefresh && cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.jwkSet();
        }

        try {
            LOG.debug("Fetching JWKS from: {}", jwksUrl);
            JWKSet jwkSet = JWKSet.load(URI.create(jwksUrl).toURL());
            jwksCache.put(jwksUrl, new CachedJwks(jwkSet, Instant.now()));
            return jwkSet;
        } catch (IOException | ParseException e) {
            // If we have a stale cache, use it rather than failing
            if (cached != null) {
                LOG.warn(
                        "Failed to refresh JWKS from {}, using stale cache: {}",
                        jwksUrl,
                        e.getMessage());
                return cached.jwkSet();
            }
            throw new CveilAuthException(
                    "Failed to fetch JWKS from " + jwksUrl + ": " + e.getMessage());
        }
    }

    /** Evict cached JWKS for a specific URL (e.g. on realm deletion). */
    public void evict(String jwksUrl) {
        jwksCache.remove(jwksUrl);
    }

    /** Clear all cached JWKS. */
    public void clearCache() {
        jwksCache.clear();
    }

    private record CachedJwks(JWKSet jwkSet, Instant fetchedAt) {
        boolean isExpired(long ttlMs) {
            return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli() > ttlMs;
        }
    }
}
