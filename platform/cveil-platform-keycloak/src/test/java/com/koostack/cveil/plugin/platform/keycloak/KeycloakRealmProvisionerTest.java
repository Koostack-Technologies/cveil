package com.koostack.cveil.plugin.platform.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link KeycloakRealmProvisioner}.
 *
 * <p>Verifies that provisioning creates a realm, a confidential client, and default roles with the
 * correct configuration. Also tests deprovisioning including the case where the realm does not
 * exist.
 */
@DisplayName("KeycloakRealmProvisioner")
class KeycloakRealmProvisionerTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String DISPLAY_NAME = "Test Tenant";
    private static final String CVEIL_CLIENT_ID = "cveil-backend";

    private Keycloak adminClient;
    private RealmsResource realmsResource;
    private RealmResource realmResource;
    private ClientsResource clientsResource;
    private ClientResource clientResource;
    private RolesResource rolesResource;
    private KeycloakRealmProvisioner provisioner;

    @BeforeEach
    void setUp() {
        adminClient = mock(Keycloak.class);
        realmsResource = mock(RealmsResource.class);
        realmResource = mock(RealmResource.class);
        clientsResource = mock(ClientsResource.class);
        clientResource = mock(ClientResource.class);
        rolesResource = mock(RolesResource.class);

        when(adminClient.realms()).thenReturn(realmsResource);
        when(adminClient.realm(TENANT_ID)).thenReturn(realmResource);
        when(realmResource.clients()).thenReturn(clientsResource);
        when(realmResource.roles()).thenReturn(rolesResource);

        provisioner =
                new KeycloakRealmProvisioner(
                        adminClient, CVEIL_CLIENT_ID, "http://localhost:8080");
    }

    // -----------------------------------------------------------------------
    // Realm provisioning
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("provisionRealm()")
    class ProvisionRealm {

        @BeforeEach
        void setUpClientMocks() {
            // Client creation chain
            ClientRepresentation clientRep = new ClientRepresentation();
            clientRep.setId("internal-client-id");
            when(clientsResource.findByClientId(CVEIL_CLIENT_ID)).thenReturn(List.of(clientRep));
            when(clientsResource.get("internal-client-id")).thenReturn(clientResource);

            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setValue("generated-secret");
            when(clientResource.getSecret()).thenReturn(cred);
        }

        @Test
        @DisplayName("creates a Keycloak realm with correct settings")
        void createsRealm() {
            provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            ArgumentCaptor<RealmRepresentation> captor =
                    ArgumentCaptor.forClass(RealmRepresentation.class);
            verify(realmsResource).create(captor.capture());
            RealmRepresentation realm = captor.getValue();

            assertEquals(TENANT_ID, realm.getRealm());
            assertEquals(DISPLAY_NAME, realm.getDisplayName());
            assertTrue(realm.isEnabled());
        }

        @Test
        @DisplayName("configures token lifetimes (15min access, 7d refresh)")
        void configuresTokenLifetimes() {
            provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            ArgumentCaptor<RealmRepresentation> captor =
                    ArgumentCaptor.forClass(RealmRepresentation.class);
            verify(realmsResource).create(captor.capture());
            RealmRepresentation realm = captor.getValue();

            assertEquals(900, realm.getAccessTokenLifespan()); // 15 minutes
            assertEquals(604800, realm.getSsoSessionMaxLifespan()); // 7 days
        }

        @Test
        @DisplayName("enables email verification and brute-force protection")
        void enablesSecurityFeatures() {
            provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            ArgumentCaptor<RealmRepresentation> captor =
                    ArgumentCaptor.forClass(RealmRepresentation.class);
            verify(realmsResource).create(captor.capture());
            RealmRepresentation realm = captor.getValue();

            assertTrue(realm.isVerifyEmail());
            assertTrue(realm.isBruteForceProtected());
            assertEquals(5, realm.getFailureFactor());
        }

        @Test
        @DisplayName("creates a confidential cveil-backend client")
        void createsConfidentialClient() {
            provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            ArgumentCaptor<ClientRepresentation> captor =
                    ArgumentCaptor.forClass(ClientRepresentation.class);
            verify(clientsResource).create(captor.capture());
            ClientRepresentation client = captor.getValue();

            assertEquals(CVEIL_CLIENT_ID, client.getClientId());
            assertTrue(client.isEnabled());
            assertFalse(client.isPublicClient());
            assertTrue(client.isServiceAccountsEnabled());
            assertTrue(client.isDirectAccessGrantsEnabled());
            assertFalse(client.isStandardFlowEnabled());
            assertEquals("openid-connect", client.getProtocol());
        }

        @Test
        @DisplayName("creates four default Cveil roles")
        void createsDefaultRoles() {
            provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            ArgumentCaptor<RoleRepresentation> captor =
                    ArgumentCaptor.forClass(RoleRepresentation.class);
            verify(rolesResource, times(4)).create(captor.capture());

            Set<String> roleNames =
                    captor.getAllValues().stream()
                            .map(RoleRepresentation::getName)
                            .collect(Collectors.toSet());

            assertEquals(
                    Set.of("consent:read", "consent:write", "admin:read", "admin:write"),
                    roleNames);
        }

        @Test
        @DisplayName("returns the generated client secret")
        void returnsClientSecret() {
            String secret = provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME);

            assertEquals("generated-secret", secret);
        }

        @Test
        @DisplayName("throws when client not found after creation")
        void throwsWhenClientNotFound() {
            when(clientsResource.findByClientId(CVEIL_CLIENT_ID)).thenReturn(List.of());

            assertThrows(
                    IllegalStateException.class,
                    () -> provisioner.provisionRealm(TENANT_ID, DISPLAY_NAME));
        }
    }

    // -----------------------------------------------------------------------
    // Realm deprovisioning
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("deprovisionRealm()")
    class DeprovisionRealm {

        @Test
        @DisplayName("removes the realm")
        void removesRealm() {
            provisioner.deprovisionRealm(TENANT_ID);

            verify(realmResource).remove();
        }

        @Test
        @DisplayName("silently ignores NotFoundException (realm already gone)")
        void ignoresNotFound() {
            doThrow(new jakarta.ws.rs.NotFoundException("Realm not found"))
                    .when(realmResource)
                    .remove();

            assertDoesNotThrow(() -> provisioner.deprovisionRealm(TENANT_ID));
        }

        @Test
        @DisplayName("re-throws unexpected exceptions")
        void rethrowsUnexpected() {
            doThrow(new RuntimeException("Keycloak down")).when(realmResource).remove();

            assertThrows(RuntimeException.class, () -> provisioner.deprovisionRealm(TENANT_ID));
        }
    }
}
