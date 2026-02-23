package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.rest.context.OperioRequestHolder;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OperioRequestHolder} — the request-scoped CDI bean
 * that carries the authenticated {@link IdentityContext} and HTTP metadata
 * between the {@code ApiKeyAuthFilter} and the {@code TenantScopeInterceptor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Initial (unpopulated) state — identity null, not authenticated</li>
 *   <li>Populated state — identity, remoteAddress, userAgent accessible</li>
 *   <li>isAuthenticated() correctness before and after set()</li>
 *   <li>Null-safe handling of optional fields (remoteAddress, userAgent)</li>
 *   <li>Overwrite semantics (subsequent set() calls overwrite)</li>
 *   <li>toString() format for authenticated and unauthenticated states</li>
 * </ul>
 */
@DisplayName("OperioRequestHolder")
class OperioRequestHolderTest {

    // -----------------------------------------------------------------------
    // Helper — build an IdentityContext for a tenant user
    // -----------------------------------------------------------------------

    private static IdentityContext tenantIdentity(
        String subject,
        String tenantId,
        Set<String> roles
    ) {
        return new IdentityContext(
            subject,
            "test-token-" + subject,
            subject + "@example.com",
            roles,
            Map.of(),
            tenantId,
            "api-key"
        );
    }

    private static IdentityContext bootstrapIdentity() {
        return new IdentityContext(
            "bootstrap-admin",
            "bootstrap-key-value",
            null,
            Set.of("super-admin"),
            Map.of(),
            null, // no tenant for bootstrap
            "bootstrap-key"
        );
    }

    // -----------------------------------------------------------------------
    // Initial (unpopulated) state
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Initial state (before set)")
    class InitialState {

        @Test
        @DisplayName("identity() returns null before set()")
        void identityIsNull() {
            OperioRequestHolder holder = new OperioRequestHolder();
            assertNull(holder.identity());
        }

        @Test
        @DisplayName("remoteAddress() returns null before set()")
        void remoteAddressIsNull() {
            OperioRequestHolder holder = new OperioRequestHolder();
            assertNull(holder.remoteAddress());
        }

        @Test
        @DisplayName("userAgent() returns null before set()")
        void userAgentIsNull() {
            OperioRequestHolder holder = new OperioRequestHolder();
            assertNull(holder.userAgent());
        }

        @Test
        @DisplayName("isAuthenticated() returns false before set()")
        void isNotAuthenticated() {
            OperioRequestHolder holder = new OperioRequestHolder();
            assertFalse(holder.isAuthenticated());
        }

        @Test
        @DisplayName("toString() shows unauthenticated before set()")
        void toStringShowsUnauthenticated() {
            OperioRequestHolder holder = new OperioRequestHolder();
            String str = holder.toString();
            assertNotNull(str);
            assertTrue(str.contains("unauthenticated"),
                "Expected 'unauthenticated' in toString but got: " + str);
        }
    }

    // -----------------------------------------------------------------------
    // Populated state — tenant user
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("After set() with tenant identity")
    class PopulatedTenantState {

        private final IdentityContext identity = tenantIdentity(
            "alice", "acme_corp", Set.of("admin", "consent-writer")
        );
        private final String remoteAddress = "192.168.1.42";
        private final String userAgent = "OperioSDK/1.0";

        private OperioRequestHolder createPopulated() {
            OperioRequestHolder holder = new OperioRequestHolder();
            holder.set(identity, remoteAddress, userAgent);
            return holder;
        }

        @Test
        @DisplayName("identity() returns the set identity")
        void identityReturned() {
            OperioRequestHolder holder = createPopulated();
            assertSame(identity, holder.identity());
        }

        @Test
        @DisplayName("identity subject matches")
        void identitySubject() {
            OperioRequestHolder holder = createPopulated();
            assertEquals("alice", holder.identity().subject());
        }

        @Test
        @DisplayName("identity tenantId matches")
        void identityTenantId() {
            OperioRequestHolder holder = createPopulated();
            assertEquals("acme_corp", holder.identity().tenantId());
        }

        @Test
        @DisplayName("identity roles match")
        void identityRoles() {
            OperioRequestHolder holder = createPopulated();
            assertEquals(Set.of("admin", "consent-writer"), holder.identity().roles());
        }

        @Test
        @DisplayName("remoteAddress() returns the set value")
        void remoteAddressReturned() {
            OperioRequestHolder holder = createPopulated();
            assertEquals(remoteAddress, holder.remoteAddress());
        }

        @Test
        @DisplayName("userAgent() returns the set value")
        void userAgentReturned() {
            OperioRequestHolder holder = createPopulated();
            assertEquals(userAgent, holder.userAgent());
        }

        @Test
        @DisplayName("isAuthenticated() returns true after set()")
        void isAuthenticated() {
            OperioRequestHolder holder = createPopulated();
            assertTrue(holder.isAuthenticated());
        }

        @Test
        @DisplayName("toString() includes subject and tenantId")
        void toStringIncludesContext() {
            OperioRequestHolder holder = createPopulated();
            String str = holder.toString();
            assertNotNull(str);
            assertTrue(str.contains("alice"),
                "Expected 'alice' in toString but got: " + str);
            assertTrue(str.contains("acme_corp"),
                "Expected 'acme_corp' in toString but got: " + str);
            assertTrue(str.contains(remoteAddress),
                "Expected remote address in toString but got: " + str);
        }
    }

    // -----------------------------------------------------------------------
    // Populated state — bootstrap (no tenant)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("After set() with bootstrap identity (no tenant)")
    class PopulatedBootstrapState {

        private final IdentityContext identity = bootstrapIdentity();

        private OperioRequestHolder createPopulated() {
            OperioRequestHolder holder = new OperioRequestHolder();
            holder.set(identity, "10.0.0.1", "curl/8.0");
            return holder;
        }

        @Test
        @DisplayName("identity() returns the bootstrap identity")
        void identityReturned() {
            OperioRequestHolder holder = createPopulated();
            assertSame(identity, holder.identity());
        }

        @Test
        @DisplayName("identity tenantId is null for bootstrap")
        void tenantIdIsNull() {
            OperioRequestHolder holder = createPopulated();
            assertNull(holder.identity().tenantId());
        }

        @Test
        @DisplayName("identity has super-admin role")
        void hasSuperAdminRole() {
            OperioRequestHolder holder = createPopulated();
            assertTrue(holder.identity().roles().contains("super-admin"));
        }

        @Test
        @DisplayName("isAuthenticated() returns true for bootstrap")
        void isAuthenticated() {
            OperioRequestHolder holder = createPopulated();
            assertTrue(holder.isAuthenticated());
        }

        @Test
        @DisplayName("toString() shows bootstrap-admin subject")
        void toStringShowsBootstrap() {
            OperioRequestHolder holder = createPopulated();
            String str = holder.toString();
            assertTrue(str.contains("bootstrap-admin"),
                "Expected 'bootstrap-admin' in toString but got: " + str);
        }

        @Test
        @DisplayName("toString() shows null tenantId for bootstrap")
        void toStringShowsNullTenant() {
            OperioRequestHolder holder = createPopulated();
            String str = holder.toString();
            assertTrue(str.contains("null"),
                "Expected 'null' tenantId in toString but got: " + str);
        }
    }

    // -----------------------------------------------------------------------
    // Null-safe optional fields
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null optional fields (remoteAddress, userAgent)")
    class NullOptionalFields {

        @Test
        @DisplayName("set() accepts null remoteAddress")
        void nullRemoteAddress() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("bob", "tenant_x", Set.of("reader"));
            holder.set(identity, null, "TestAgent/1.0");

            assertTrue(holder.isAuthenticated());
            assertNull(holder.remoteAddress());
            assertEquals("TestAgent/1.0", holder.userAgent());
        }

        @Test
        @DisplayName("set() accepts null userAgent")
        void nullUserAgent() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("bob", "tenant_x", Set.of("reader"));
            holder.set(identity, "10.0.0.1", null);

            assertTrue(holder.isAuthenticated());
            assertEquals("10.0.0.1", holder.remoteAddress());
            assertNull(holder.userAgent());
        }

        @Test
        @DisplayName("set() accepts all-null optional fields")
        void allNullOptionalFields() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("charlie", "tenant_y", Set.of());
            holder.set(identity, null, null);

            assertTrue(holder.isAuthenticated());
            assertEquals("charlie", holder.identity().subject());
            assertNull(holder.remoteAddress());
            assertNull(holder.userAgent());
        }
    }

    // -----------------------------------------------------------------------
    // Overwrite semantics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Overwrite semantics (multiple set() calls)")
    class OverwriteSemantics {

        @Test
        @DisplayName("second set() overwrites identity")
        void overwriteIdentity() {
            OperioRequestHolder holder = new OperioRequestHolder();

            IdentityContext first = tenantIdentity("alice", "tenant_a", Set.of("admin"));
            IdentityContext second = tenantIdentity("bob", "tenant_b", Set.of("reader"));

            holder.set(first, "10.0.0.1", "Agent1");
            assertEquals("alice", holder.identity().subject());
            assertEquals("tenant_a", holder.identity().tenantId());

            holder.set(second, "10.0.0.2", "Agent2");
            assertEquals("bob", holder.identity().subject());
            assertEquals("tenant_b", holder.identity().tenantId());
        }

        @Test
        @DisplayName("second set() overwrites remoteAddress")
        void overwriteRemoteAddress() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("alice", "tenant_a", Set.of());

            holder.set(identity, "10.0.0.1", "Agent");
            assertEquals("10.0.0.1", holder.remoteAddress());

            holder.set(identity, "192.168.0.1", "Agent");
            assertEquals("192.168.0.1", holder.remoteAddress());
        }

        @Test
        @DisplayName("second set() overwrites userAgent")
        void overwriteUserAgent() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("alice", "tenant_a", Set.of());

            holder.set(identity, "10.0.0.1", "Agent1");
            assertEquals("Agent1", holder.userAgent());

            holder.set(identity, "10.0.0.1", "Agent2");
            assertEquals("Agent2", holder.userAgent());
        }

        @Test
        @DisplayName("isAuthenticated remains true after overwrite")
        void stillAuthenticatedAfterOverwrite() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext first = tenantIdentity("alice", "t1", Set.of());
            IdentityContext second = tenantIdentity("bob", "t2", Set.of());

            holder.set(first, null, null);
            assertTrue(holder.isAuthenticated());

            holder.set(second, null, null);
            assertTrue(holder.isAuthenticated());
        }
    }

    // -----------------------------------------------------------------------
    // Identity with empty roles
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Identity with empty roles")
    class EmptyRoles {

        @Test
        @DisplayName("identity with empty roles set is still authenticated")
        void emptyRolesAuthenticated() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("dave", "tenant_z", Set.of());
            holder.set(identity, "10.0.0.1", "Agent");

            assertTrue(holder.isAuthenticated());
            assertNotNull(holder.identity().roles());
            assertTrue(holder.identity().roles().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Identity with multiple roles
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Identity with multiple roles")
    class MultipleRoles {

        @Test
        @DisplayName("all roles are preserved through the holder")
        void allRolesPreserved() {
            OperioRequestHolder holder = new OperioRequestHolder();
            Set<String> roles = Set.of("admin", "consent-writer", "consent-reader", "audit-viewer");
            IdentityContext identity = tenantIdentity("eve", "tenant_multi", roles);
            holder.set(identity, "10.0.0.1", "Agent");

            assertEquals(roles, holder.identity().roles());
            assertEquals(4, holder.identity().roles().size());
        }
    }

    // -----------------------------------------------------------------------
    // Provider and email pass-through
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Identity provider and email pass-through")
    class ProviderAndEmail {

        @Test
        @DisplayName("provider is accessible through the holder")
        void providerAccessible() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("frank", "tenant_f", Set.of("reader"));
            holder.set(identity, null, null);

            assertEquals("api-key", holder.identity().provider());
        }

        @Test
        @DisplayName("email is accessible through the holder")
        void emailAccessible() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("frank", "tenant_f", Set.of("reader"));
            holder.set(identity, null, null);

            assertEquals("frank@example.com", holder.identity().email());
        }

        @Test
        @DisplayName("token is accessible through the holder")
        void tokenAccessible() {
            OperioRequestHolder holder = new OperioRequestHolder();
            IdentityContext identity = tenantIdentity("frank", "tenant_f", Set.of("reader"));
            holder.set(identity, null, null);

            assertEquals("test-token-frank", holder.identity().token());
        }
    }
}
