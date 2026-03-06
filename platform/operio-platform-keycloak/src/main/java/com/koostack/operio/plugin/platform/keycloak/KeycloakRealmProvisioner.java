package com.koostack.operio.plugin.platform.keycloak;

import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions and deprovisions Keycloak realms for Operio tenants.
 *
 * <p>Each tenant gets its own realm with:
 *
 * <ul>
 *   <li>A confidential {@code operio-backend} client for token exchange
 *   <li>Default Operio roles ({@code consent:read}, {@code consent:write}, {@code admin:read},
 *       {@code admin:write})
 *   <li>Token lifetimes: 15-minute access, 7-day refresh
 *   <li>Email verification required
 *   <li>Brute-force protection enabled
 * </ul>
 */
public class KeycloakRealmProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakRealmProvisioner.class);

    private static final List<String> DEFAULT_ROLES =
            List.of("consent:read", "consent:write", "admin:read", "admin:write");

    private static final int ACCESS_TOKEN_LIFESPAN_SECONDS = 900; // 15 minutes
    private static final int REFRESH_TOKEN_LIFESPAN_SECONDS = 604800; // 7 days

    private final Keycloak adminClient;
    private final String operioClientId;

    public KeycloakRealmProvisioner(Keycloak adminClient, String operioClientId) {
        this.adminClient = adminClient;
        this.operioClientId = operioClientId;
    }

    /**
     * Create a new Keycloak realm for the given tenant.
     *
     * @param tenantId used as the realm name
     * @param displayName human-readable display name
     * @return the generated client secret for the operio-backend client
     */
    public String provisionRealm(String tenantId, String displayName) {
        LOG.info("Provisioning Keycloak realm for tenant '{}'", tenantId);

        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(tenantId);
        realm.setDisplayName(displayName);
        realm.setEnabled(true);

        // Token lifetimes
        realm.setAccessTokenLifespan(ACCESS_TOKEN_LIFESPAN_SECONDS);
        realm.setSsoSessionMaxLifespan(REFRESH_TOKEN_LIFESPAN_SECONDS);
        realm.setOfflineSessionMaxLifespan(REFRESH_TOKEN_LIFESPAN_SECONDS);

        // Email verification required
        realm.setVerifyEmail(true);

        // Brute force protection
        realm.setBruteForceProtected(true);
        realm.setMaxFailureWaitSeconds(900);
        realm.setFailureFactor(5);

        adminClient.realms().create(realm);
        LOG.info("Realm '{}' created", tenantId);

        RealmResource realmResource = adminClient.realm(tenantId);

        // Create confidential client
        String clientSecret = createOperioClient(realmResource, tenantId);

        // Create default roles
        createDefaultRoles(realmResource);

        LOG.info("Realm '{}' fully provisioned", tenantId);
        return clientSecret;
    }

    /**
     * Delete the Keycloak realm for the given tenant. Silently ignores the case where the realm
     * does not exist.
     */
    public void deprovisionRealm(String tenantId) {
        LOG.info("Deprovisioning Keycloak realm for tenant '{}'", tenantId);
        try {
            adminClient.realm(tenantId).remove();
            LOG.info("Realm '{}' deleted", tenantId);
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.info("Realm '{}' does not exist — nothing to deprovision", tenantId);
        } catch (Exception e) {
            LOG.error("Failed to deprovision realm '{}': {}", tenantId, e.getMessage(), e);
            throw e;
        }
    }

    private String createOperioClient(RealmResource realmResource, String tenantId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(operioClientId);
        client.setName("Operio Backend (" + tenantId + ")");
        client.setEnabled(true);
        client.setProtocol("openid-connect");
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setDirectAccessGrantsEnabled(true); // Resource Owner Password Credentials
        client.setStandardFlowEnabled(false);
        client.setAttributes(
                Map.of("access.token.lifespan", String.valueOf(ACCESS_TOKEN_LIFESPAN_SECONDS)));

        realmResource.clients().create(client);

        // Retrieve the generated client secret
        List<ClientRepresentation> clients = realmResource.clients().findByClientId(operioClientId);
        if (clients.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to find operio client after creation in realm " + tenantId);
        }

        String internalId = clients.get(0).getId();
        String secret = realmResource.clients().get(internalId).getSecret().getValue();

        LOG.debug("Operio client created in realm '{}', clientId='{}'", tenantId, operioClientId);
        return secret;
    }

    private void createDefaultRoles(RealmResource realmResource) {
        RolesResource rolesResource = realmResource.roles();
        for (String roleName : DEFAULT_ROLES) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(roleName);
            role.setDescription("Operio default role: " + roleName);
            rolesResource.create(role);
        }
        LOG.debug("Created {} default roles", DEFAULT_ROLES.size());
    }
}
