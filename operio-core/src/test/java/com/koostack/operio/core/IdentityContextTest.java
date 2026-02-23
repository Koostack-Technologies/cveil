package com.koostack.operio.core;

import com.koostack.operio.core.Models.IdentityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IdentityContext} — the immutable identity record resolved
 * from bearer tokens or ambient security context.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code of(subject, token)} factory — convenience for minimal identity</li>
 *   <li>{@code anonymous()} factory — sentinel for unauthenticated contexts</li>
 *   <li>Full canonical constructor with all enriched fields</li>
 *   <li>Record accessor semantics (field values, equals, hashCode, toString)</li>
 *   <li>Immutability of collection fields (roles, claims)</li>
 * </ul>
 */
@DisplayName("IdentityContext (record)")
class IdentityContextTest {

    // -----------------------------------------------------------------------
    // Factory: of(subject, token)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("of(subject, token) factory")
    class OfFactory {

        @Test
        @DisplayName("creates identity with subject and token, defaults for everything else")
        void createsMinimalIdentity() {
            IdentityContext ctx = IdentityContext.of("user-123", "tok_abc");

            assertEquals("user-123", ctx.subject());
            assertEquals("tok_abc", ctx.token());
            assertNull(ctx.email(), "email should be null");
            assertNotNull(ctx.roles(), "roles should never be null");
            assertTrue(ctx.roles().isEmpty(), "roles should be empty");
            assertNotNull(ctx.claims(), "claims should never be null");
            assertTrue(ctx.claims().isEmpty(), "claims should be empty");
            assertNull(ctx.tenantId(), "tenantId should be null");
            assertNull(ctx.provider(), "provider should be null");
        }

        @Test
        @DisplayName("of() with null token is allowed")
        void allowsNullToken() {
            IdentityContext ctx = IdentityContext.of("user-123", null);

            assertEquals("user-123", ctx.subject());
            assertNull(ctx.token());
        }

        @Test
        @DisplayName("of() with null subject is allowed (no validation in record)")
        void allowsNullSubject() {
            IdentityContext ctx = IdentityContext.of(null, "tok_abc");

            assertNull(ctx.subject());
            assertEquals("tok_abc", ctx.token());
        }
    }

    // -----------------------------------------------------------------------
    // Factory: anonymous()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("anonymous() factory")
    class AnonymousFactory {

        @Test
        @DisplayName("creates identity with subject='anonymous' and null token")
        void createsAnonymousIdentity() {
            IdentityContext ctx = IdentityContext.anonymous();

            assertEquals("anonymous", ctx.subject());
            assertNull(ctx.token(), "token should be null for anonymous");
            assertNull(ctx.email(), "email should be null for anonymous");
            assertNotNull(ctx.roles(), "roles should never be null");
            assertTrue(ctx.roles().isEmpty(), "roles should be empty for anonymous");
            assertNotNull(ctx.claims(), "claims should never be null");
            assertTrue(ctx.claims().isEmpty(), "claims should be empty for anonymous");
            assertNull(ctx.tenantId(), "tenantId should be null for anonymous");
            assertNull(ctx.provider(), "provider should be null for anonymous");
        }

        @Test
        @DisplayName("multiple calls to anonymous() produce equal instances")
        void anonymousInstancesAreEqual() {
            IdentityContext a = IdentityContext.anonymous();
            IdentityContext b = IdentityContext.anonymous();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // -----------------------------------------------------------------------
    // Full canonical constructor (enriched fields)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Canonical constructor (all fields)")
    class CanonicalConstructor {

        @Test
        @DisplayName("all enriched fields are accessible")
        void allFieldsAccessible() {
            Set<String> roles = Set.of("admin", "viewer");
            Map<String, Object> claims = Map.of("org", "acme", "tier", 3);

            IdentityContext ctx = new IdentityContext(
                "user-456",
                "tok_xyz",
                "alice@acme.com",
                roles,
                claims,
                "acme_corp",
                "okta"
            );

            assertEquals("user-456", ctx.subject());
            assertEquals("tok_xyz", ctx.token());
            assertEquals("alice@acme.com", ctx.email());
            assertEquals(roles, ctx.roles());
            assertEquals(claims, ctx.claims());
            assertEquals("acme_corp", ctx.tenantId());
            assertEquals("okta", ctx.provider());
        }

        @Test
        @DisplayName("roles set contains expected entries")
        void rolesContainExpectedEntries() {
            Set<String> roles = Set.of("super-admin", "tenant-admin", "viewer");

            IdentityContext ctx = new IdentityContext(
                "user-789", null, null, roles, Map.of(), "t1", "auth0"
            );

            assertEquals(3, ctx.roles().size());
            assertTrue(ctx.roles().contains("super-admin"));
            assertTrue(ctx.roles().contains("tenant-admin"));
            assertTrue(ctx.roles().contains("viewer"));
        }

        @Test
        @DisplayName("claims map contains expected entries")
        void claimsContainExpectedEntries() {
            Map<String, Object> claims = Map.of(
                "iss", "https://auth.acme.com",
                "aud", "operio-api",
                "custom_flag", true
            );

            IdentityContext ctx = new IdentityContext(
                "user-789", null, null, Set.of(), claims, null, "keycloak"
            );

            assertEquals("https://auth.acme.com", ctx.claims().get("iss"));
            assertEquals("operio-api", ctx.claims().get("aud"));
            assertEquals(true, ctx.claims().get("custom_flag"));
        }

        @Test
        @DisplayName("empty roles and claims are allowed")
        void emptyCollectionsAllowed() {
            IdentityContext ctx = new IdentityContext(
                "user-0", null, null, Set.of(), Map.of(), null, null
            );

            assertTrue(ctx.roles().isEmpty());
            assertTrue(ctx.claims().isEmpty());
        }

        @Test
        @DisplayName("null roles and claims are allowed (no validation in record)")
        void nullCollectionsAllowed() {
            // The record itself doesn't enforce non-null; factories do.
            // Direct construction with nulls should not throw.
            IdentityContext ctx = new IdentityContext(
                "user-0", null, null, null, null, null, null
            );

            assertNull(ctx.roles());
            assertNull(ctx.claims());
        }
    }

    // -----------------------------------------------------------------------
    // Record semantics (equals, hashCode, toString)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Record semantics")
    class RecordSemantics {

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            IdentityContext a = new IdentityContext(
                "u1", "t1", "e@x.com", Set.of("admin"), Map.of("k", "v"), "tenant", "okta"
            );
            IdentityContext b = new IdentityContext(
                "u1", "t1", "e@x.com", Set.of("admin"), Map.of("k", "v"), "tenant", "okta"
            );
            IdentityContext c = new IdentityContext(
                "u2", "t1", "e@x.com", Set.of("admin"), Map.of("k", "v"), "tenant", "okta"
            );

            assertEquals(a, b, "Identical field values should be equal");
            assertNotEquals(a, c, "Different subject should not be equal");
        }

        @Test
        @DisplayName("hashCode consistent with equals")
        void hashCodeConsistentWithEquals() {
            IdentityContext a = new IdentityContext(
                "u1", "t1", "e@x.com", Set.of("admin"), Map.of("k", "v"), "tenant", "okta"
            );
            IdentityContext b = new IdentityContext(
                "u1", "t1", "e@x.com", Set.of("admin"), Map.of("k", "v"), "tenant", "okta"
            );

            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("equals: different roles → not equal")
        void differentRolesNotEqual() {
            IdentityContext a = new IdentityContext(
                "u1", "t1", null, Set.of("admin"), Map.of(), "t", null
            );
            IdentityContext b = new IdentityContext(
                "u1", "t1", null, Set.of("viewer"), Map.of(), "t", null
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different tenantId → not equal")
        void differentTenantNotEqual() {
            IdentityContext a = new IdentityContext(
                "u1", null, null, Set.of(), Map.of(), "tenant-a", null
            );
            IdentityContext b = new IdentityContext(
                "u1", null, null, Set.of(), Map.of(), "tenant-b", null
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different provider → not equal")
        void differentProviderNotEqual() {
            IdentityContext a = new IdentityContext(
                "u1", null, null, Set.of(), Map.of(), null, "okta"
            );
            IdentityContext b = new IdentityContext(
                "u1", null, null, Set.of(), Map.of(), null, "auth0"
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different claims → not equal")
        void differentClaimsNotEqual() {
            IdentityContext a = new IdentityContext(
                "u1", null, null, Set.of(), Map.of("k", "v1"), null, null
            );
            IdentityContext b = new IdentityContext(
                "u1", null, null, Set.of(), Map.of("k", "v2"), null, null
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equals: different email → not equal")
        void differentEmailNotEqual() {
            IdentityContext a = new IdentityContext(
                "u1", null, "a@x.com", Set.of(), Map.of(), null, null
            );
            IdentityContext b = new IdentityContext(
                "u1", null, "b@x.com", Set.of(), Map.of(), null, null
            );

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("not equal to null or different type")
        void notEqualToNullOrDifferentType() {
            IdentityContext ctx = IdentityContext.of("u1", "t1");

            assertNotEquals(null, ctx);
            assertNotEquals("not-an-identity", ctx);
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringIncludesKeyFields() {
            IdentityContext ctx = new IdentityContext(
                "user-42", "tok_secret", "user@example.com",
                Set.of("admin"), Map.of("iss", "test"),
                "acme_corp", "okta"
            );

            String s = ctx.toString();
            assertTrue(s.contains("user-42"), "toString should contain subject");
            assertTrue(s.contains("acme_corp"), "toString should contain tenantId");
            assertTrue(s.contains("okta"), "toString should contain provider");
            assertTrue(s.contains("user@example.com"), "toString should contain email");
        }
    }

    // -----------------------------------------------------------------------
    // Immutability of collection fields (from factories)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("roles from of() factory are unmodifiable")
        void rolesFromOfAreUnmodifiable() {
            IdentityContext ctx = IdentityContext.of("u1", "t1");

            assertThrows(UnsupportedOperationException.class, () ->
                ctx.roles().add("hacker")
            );
        }

        @Test
        @DisplayName("claims from of() factory are unmodifiable")
        void claimsFromOfAreUnmodifiable() {
            IdentityContext ctx = IdentityContext.of("u1", "t1");

            assertThrows(UnsupportedOperationException.class, () ->
                ctx.claims().put("injected", "value")
            );
        }

        @Test
        @DisplayName("roles from anonymous() factory are unmodifiable")
        void rolesFromAnonymousAreUnmodifiable() {
            IdentityContext ctx = IdentityContext.anonymous();

            assertThrows(UnsupportedOperationException.class, () ->
                ctx.roles().add("hacker")
            );
        }

        @Test
        @DisplayName("claims from anonymous() factory are unmodifiable")
        void claimsFromAnonymousAreUnmodifiable() {
            IdentityContext ctx = IdentityContext.anonymous();

            assertThrows(UnsupportedOperationException.class, () ->
                ctx.claims().put("injected", "value")
            );
        }
    }
}
