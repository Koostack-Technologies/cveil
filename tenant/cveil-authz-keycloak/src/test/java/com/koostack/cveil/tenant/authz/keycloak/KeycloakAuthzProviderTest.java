package com.koostack.cveil.tenant.authz.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link KeycloakAuthzProvider}. */
@DisplayName("KeycloakAuthzProvider")
@ExtendWith(MockitoExtension.class)
class KeycloakAuthzProviderTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String USER_ID = "user-123";

    private KeycloakAuthzProvider provider;
    private Keycloak adminClient;
    private RealmResource realmResource;
    private UsersResource usersResource;
    private UserResource userResource;
    private RolesResource rolesResource;
    private RoleResource roleResource;
    private RoleMappingResource roleMappingResource;
    private RoleScopeResource roleScopeResource;

    @BeforeEach
    void setUp() throws Exception {
        provider =
                new KeycloakAuthzProvider(
                        new KeycloakAuthzConfig(
                                "http://keycloak:8180", "master", "admin-cli", "admin", "admin"));

        // Replace admin client with mock
        adminClient = mock(Keycloak.class);
        realmResource = mock(RealmResource.class);
        usersResource = mock(UsersResource.class);
        userResource = mock(UserResource.class);
        rolesResource = mock(RolesResource.class);
        roleResource = mock(RoleResource.class);
        roleMappingResource = mock(RoleMappingResource.class);
        roleScopeResource = mock(RoleScopeResource.class);

        lenient().when(adminClient.realm(TENANT_ID)).thenReturn(realmResource);
        lenient().when(realmResource.users()).thenReturn(usersResource);
        lenient().when(realmResource.roles()).thenReturn(rolesResource);
        lenient().when(usersResource.get(USER_ID)).thenReturn(userResource);
        lenient().when(userResource.roles()).thenReturn(roleMappingResource);
        lenient().when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);

        Field field = KeycloakAuthzProvider.class.getDeclaredField("adminClient");
        field.setAccessible(true);
        field.set(provider, adminClient);
    }

    // -------------------------------------------------------------------
    // Plugin SPI
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Plugin SPI")
    class PluginSpi {

        @Test
        @DisplayName("getName returns 'keycloak-authz'")
        void getName() {
            assertEquals("keycloak-authz", provider.getName());
        }

        @Test
        @DisplayName("getPriority returns 100")
        void getPriority() {
            assertEquals(100, provider.getPriority());
        }

        @Test
        @DisplayName("getConfigurationType returns KeycloakAuthzConfig.class")
        void getConfigurationType() {
            assertEquals(KeycloakAuthzConfig.class, provider.getConfigurationType());
        }
    }

    // -------------------------------------------------------------------
    // Role management
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Role management")
    class RoleManagement {

        @Test
        @DisplayName("assigns a realm-level role to a user")
        void assignRole() {
            RoleRepresentation roleRep = new RoleRepresentation();
            roleRep.setName("consent:read");
            when(rolesResource.get("consent:read")).thenReturn(roleResource);
            when(roleResource.toRepresentation()).thenReturn(roleRep);

            provider.assignRole(TENANT_ID, USER_ID, "consent:read");

            verify(roleScopeResource).add(List.of(roleRep));
        }

        @Test
        @DisplayName("removes a realm-level role from a user")
        void removeRole() {
            RoleRepresentation roleRep = new RoleRepresentation();
            roleRep.setName("admin:write");
            when(rolesResource.get("admin:write")).thenReturn(roleResource);
            when(roleResource.toRepresentation()).thenReturn(roleRep);

            provider.removeRole(TENANT_ID, USER_ID, "admin:write");

            verify(roleScopeResource).remove(List.of(roleRep));
        }

        @Test
        @DisplayName("returns user's effective realm-level roles")
        void getUserRoles() {
            RoleRepresentation r1 = new RoleRepresentation();
            r1.setName("consent:read");
            RoleRepresentation r2 = new RoleRepresentation();
            r2.setName("admin:write");
            when(roleScopeResource.listEffective()).thenReturn(List.of(r1, r2));

            Set<String> roles = provider.getUserRoles(TENANT_ID, USER_ID);

            assertEquals(Set.of("consent:read", "admin:write"), roles);
        }

        @Test
        @DisplayName("getUserRoles returns an immutable set")
        void rolesAreImmutable() {
            when(roleScopeResource.listEffective()).thenReturn(List.of());

            Set<String> roles = provider.getUserRoles(TENANT_ID, USER_ID);

            assertThrows(UnsupportedOperationException.class, () -> roles.add("injected"));
        }

        @Test
        @DisplayName("getUserRoles returns empty set when user has no roles")
        void emptyRoles() {
            when(roleScopeResource.listEffective()).thenReturn(List.of());

            Set<String> roles = provider.getUserRoles(TENANT_ID, USER_ID);

            assertTrue(roles.isEmpty());
        }
    }

    // -------------------------------------------------------------------
    // Config validation
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("Config validation")
    class ConfigValidation {

        @Test
        @DisplayName("rejects blank serverUrl")
        void blankServerUrl() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new KeycloakAuthzConfig("", "master", "admin-cli", "admin", "admin"));
        }

        @Test
        @DisplayName("rejects null adminUsername")
        void nullAdminUsername() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new KeycloakAuthzConfig(
                                    "http://localhost:8180", "master", "admin-cli", null, "admin"));
        }

        @Test
        @DisplayName("rejects null adminPassword")
        void nullAdminPassword() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new KeycloakAuthzConfig(
                                    "http://localhost:8180", "master", "admin-cli", "admin", null));
        }
    }
}
