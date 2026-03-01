package com.koostack.operio.plugin.platform.keycloak;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.IdentityContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JwksTokenVerifier} — the JWKS-based JWT verifier used
 * on the hot path of every authenticated request.
 *
 * <p>Uses an in-memory RSA key pair and a local HTTP server to serve JWKS,
 * exercising the full crypto verification path without any external dependencies.
 */
@DisplayName("JwksTokenVerifier")
class JwksTokenVerifierTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String KEYCLOAK_ISSUER = "http://localhost:8180/realms/test-tenant";
    private static final String OIDC_ISSUER = "https://auth0.example.com/";

    private static KeyPair keyPair1;
    private static RSAKey rsaKey1;
    private static KeyPair keyPair2;
    private static RSAKey rsaKey2;

    private static HttpServer jwksServer;
    private static String jwksUrl;
    private static volatile String currentJwksBody;
    private static volatile boolean jwksServerShouldFail;

    private JwksTokenVerifier verifier;

    @BeforeAll
    static void startJwksServer() throws Exception {
        keyPair1 = generateKeyPair();
        rsaKey1 = toRsaKey(keyPair1, "key-1");
        keyPair2 = generateKeyPair();
        rsaKey2 = toRsaKey(keyPair2, "key-2");

        currentJwksBody = new JWKSet(rsaKey1.toPublicJWK()).toString();
        jwksServerShouldFail = false;

        jwksServer = HttpServer.create(new InetSocketAddress(0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            if (jwksServerShouldFail) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = currentJwksBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        jwksServer.start();

        jwksUrl = "http://localhost:" + jwksServer.getAddress().getPort() + "/jwks";
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        verifier = new JwksTokenVerifier(500); // 500ms cache TTL for fast tests
        currentJwksBody = new JWKSet(rsaKey1.toPublicJWK()).toString();
        jwksServerShouldFail = false;
    }

    // -----------------------------------------------------------------------
    // Valid token verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Valid token verification")
    class ValidTokenVerification {

        @Test
        @DisplayName("verifies a correctly signed JWT and returns IdentityContext")
        void verifiesValidToken() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("email", "user@test.com")
                .issuer(KEYCLOAK_ISSUER)
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertNotNull(result);
            assertEquals("user-123", result.subject());
            assertEquals("user@test.com", result.email());
            assertEquals(TENANT_ID, result.tenantId());
            assertEquals(token, result.token());
        }

        @Test
        @DisplayName("verifies token with minimal claims (subject only)")
        void verifiesMinimalToken() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-456")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals("user-456", result.subject());
            assertNull(result.email());
            assertTrue(result.roles().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Claims extraction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Claims extraction")
    class ClaimsExtraction {

        @Test
        @DisplayName("extracts Keycloak realm_access.roles")
        void extractsKeycloakRoles() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("realm_access", Map.of("roles", List.of("consent:read", "admin:write")))
                .issuer(KEYCLOAK_ISSUER)
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals(Set.of("consent:read", "admin:write"), result.roles());
        }

        @Test
        @DisplayName("extracts standard OIDC 'roles' claim")
        void extractsOidcRoles() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("roles", List.of("viewer", "editor"))
                .issuer(OIDC_ISSUER)
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals(Set.of("viewer", "editor"), result.roles());
        }

        @Test
        @DisplayName("merges roles from both Keycloak and standard OIDC formats")
        void mergesBothRoleFormats() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("realm_access", Map.of("roles", List.of("consent:read")))
                .claim("roles", List.of("custom-role"))
                .issuer(KEYCLOAK_ISSUER)
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals(Set.of("consent:read", "custom-role"), result.roles());
        }

        @Test
        @DisplayName("passes tenant ID through to IdentityContext")
        void passesTenantId() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, "custom-tenant");

            assertEquals("custom-tenant", result.tenantId());
        }

        @Test
        @DisplayName("includes all JWT claims in the claims map")
        void includesAllClaimsInMap() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("email", "test@example.com")
                .claim("custom_claim", "custom_value")
                .issuer(KEYCLOAK_ISSUER)
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertNotNull(result.claims());
            assertEquals("test@example.com", result.claims().get("email"));
            assertEquals("custom_value", result.claims().get("custom_claim"));
        }

        @Test
        @DisplayName("claims map is immutable")
        void claimsMapIsImmutable() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertThrows(UnsupportedOperationException.class, () ->
                result.claims().put("hack", "value")
            );
        }

        @Test
        @DisplayName("roles set is immutable")
        void rolesSetIsImmutable() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .claim("realm_access", Map.of("roles", List.of("consent:read")))
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertThrows(UnsupportedOperationException.class, () ->
                result.roles().add("injected-role")
            );
        }
    }

    // -----------------------------------------------------------------------
    // Provider detection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Provider detection")
    class ProviderDetection {

        @Test
        @DisplayName("detects 'keycloak' when issuer contains /realms/")
        void detectsKeycloak() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer("http://keycloak:8080/realms/my-tenant")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals("keycloak", result.provider());
        }

        @Test
        @DisplayName("detects 'oidc' when issuer does not contain /realms/")
        void detectsOidc() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer("https://auth0.example.com/")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals("oidc", result.provider());
        }

        @Test
        @DisplayName("defaults to 'oidc' when no issuer claim is present")
        void defaultsToOidcWithoutIssuer() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .build());

            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals("oidc", result.provider());
        }
    }

    // -----------------------------------------------------------------------
    // Cache management & key rotation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cache management")
    class CacheManagement {

        @Test
        @DisplayName("caches JWKS and reuses for subsequent verifications")
        void cachesJwks() throws Exception {
            String token1 = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-1").build());
            String token2 = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-2").build());

            verifier.verify(jwksUrl, token1, TENANT_ID);
            IdentityContext result = verifier.verify(jwksUrl, token2, TENANT_ID);

            assertEquals("user-2", result.subject());
        }

        @Test
        @DisplayName("evict() removes cached JWKS for a specific URL")
        void evictRemovesCachedUrl() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-1").build());

            // Populate cache
            verifier.verify(jwksUrl, token, TENANT_ID);

            // Evict and re-verify (should re-fetch)
            verifier.evict(jwksUrl);
            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);

            assertEquals("user-1", result.subject());
        }

        @Test
        @DisplayName("clearCache() removes all cached JWKS")
        void clearCacheRemovesAll() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-1").build());

            verifier.verify(jwksUrl, token, TENANT_ID);
            verifier.clearCache();

            // Should re-fetch and still succeed
            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);
            assertEquals("user-1", result.subject());
        }

        @Test
        @DisplayName("retries with refreshed JWKS on key rotation")
        void retriesOnKeyRotation() throws Exception {
            // Populate cache with key1
            String token1 = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-1").build());
            verifier.verify(jwksUrl, token1, TENANT_ID);

            // Sign a token with key2 (not in cached JWKS)
            String token2 = signJwt(keyPair2, rsaKey2, new JWTClaimsSet.Builder()
                .subject("user-2").build());

            // Update JWKS server to serve both keys
            currentJwksBody = new JWKSet(List.of(
                rsaKey1.toPublicJWK(), rsaKey2.toPublicJWK()
            )).toString();

            // Verification should succeed: cache miss → refresh → retry
            IdentityContext result = verifier.verify(jwksUrl, token2, TENANT_ID);

            assertEquals("user-2", result.subject());
        }

        @Test
        @DisplayName("uses stale cache when JWKS fetch fails")
        void usesStaleCacheOnFetchFailure() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-1").build());

            // Populate cache
            verifier.verify(jwksUrl, token, TENANT_ID);

            // Wait for cache to expire
            Thread.sleep(600);

            // Make server fail
            jwksServerShouldFail = true;

            // Should use stale cached JWKS
            IdentityContext result = verifier.verify(jwksUrl, token, TENANT_ID);
            assertEquals("user-1", result.subject());
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("throws OperioAuthException when 'sub' claim is missing")
        void throwsOnMissingSub() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .claim("email", "user@test.com")
                .build());

            OperioAuthException ex = assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, token, TENANT_ID)
            );
            assertTrue(ex.getMessage().contains("sub"));
        }

        @Test
        @DisplayName("throws OperioAuthException when 'sub' claim is blank")
        void throwsOnBlankSub() throws Exception {
            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("  ")
                .build());

            assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, token, TENANT_ID)
            );
        }

        @Test
        @DisplayName("throws OperioAuthException when token is signed with unknown key")
        void throwsOnWrongKey() throws Exception {
            // Sign with key2 but JWKS only serves key1
            String token = signJwt(keyPair2, rsaKey2, new JWTClaimsSet.Builder()
                .subject("user-123")
                .build());

            assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, token, TENANT_ID)
            );
        }

        @Test
        @DisplayName("throws OperioAuthException on malformed token")
        void throwsOnMalformedToken() {
            assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, "not.a.valid.jwt.token", TENANT_ID)
            );
        }

        @Test
        @DisplayName("throws OperioAuthException on garbage input")
        void throwsOnGarbageInput() {
            assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, "garbage", TENANT_ID)
            );
        }

        @Test
        @DisplayName("throws OperioAuthException when JWKS URL is unreachable and no cache")
        void throwsOnUnreachableJwksWithNoCache() throws Exception {
            jwksServerShouldFail = true;

            String token = signJwt(keyPair1, rsaKey1, new JWTClaimsSet.Builder()
                .subject("user-123")
                .build());

            assertThrows(OperioAuthException.class, () ->
                verifier.verify(jwksUrl, token, TENANT_ID)
            );
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static RSAKey toRsaKey(KeyPair keyPair, String keyId) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID(keyId)
            .build();
    }

    private static String signJwt(KeyPair keyPair, RSAKey rsaKey, JWTClaimsSet claims)
        throws Exception {

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(rsaKey.getKeyID())
            .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }
}
