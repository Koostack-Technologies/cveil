package com.koostack.operio.plugin.platform.keycloak;

import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
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
            List.of(
                    "consent:read",
                    "consent:write",
                    "admin:read",
                    "admin:write",
                    "tenant-admin",
                    "tenant-write",
                    "tenant-read");

    private static final int ACCESS_TOKEN_LIFESPAN_SECONDS = 900; // 15 minutes
    private static final int REFRESH_TOKEN_LIFESPAN_SECONDS = 604800; // 7 days

    private final Keycloak adminClient;
    private final String operioClientId;

    /** Result of provisioning a Keycloak realm. */
    public record ProvisionResult(String realmId, String clientSecret) {}

    public KeycloakRealmProvisioner(Keycloak adminClient, String operioClientId) {
        this.adminClient = adminClient;
        this.operioClientId = operioClientId;
    }

    /**
     * Create a new Keycloak realm using the realmId as both the realm name and display name.
     *
     * @param realmId used as the realm name (e.g. {@code t_abc123})
     * @return provision result containing realm ID and client secret
     */
    public ProvisionResult provisionRealm(String realmId) {
        String clientSecret = provisionRealm(realmId, realmId);
        return new ProvisionResult(realmId, clientSecret);
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

    /**
     * Create a root admin user in the given realm with the {@code tenant-admin} role and the
     * Keycloak {@code UPDATE_PASSWORD} required action so the user sets their own password.
     *
     * @param realmId the realm (e.g. {@code t_abc123})
     * @param email the root admin email address
     */
    public void createRootUser(String realmId, String email) {
        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if root user already exists
        List<UserRepresentation> existing = realm.users().searchByEmail(email, true);
        if (!existing.isEmpty()) {
            LOG.info("Root user already exists in realm '{}': email={}", realmId, email);
            return;
        }

        UserRepresentation user = new UserRepresentation();
        user.setUsername("root");
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setRequiredActions(List.of("UPDATE_PASSWORD"));

        var response = realm.users().create(user);
        try {
            if (response.getStatus() == 201) {
                String userId = extractUserId(response);
                if (userId != null) {
                    // Assign tenant-admin role
                    try {
                        RoleRepresentation adminRole =
                                realm.roles().get("tenant-admin").toRepresentation();
                        realm.users().get(userId).roles().realmLevel().add(List.of(adminRole));
                    } catch (Exception e) {
                        LOG.warn(
                                "Failed to assign tenant-admin role to root user: {}",
                                e.getMessage());
                    }
                    // Send password setup email
                    try {
                        realm.users().get(userId).executeActionsEmail(List.of("UPDATE_PASSWORD"));
                    } catch (Exception e) {
                        LOG.warn("Failed to send password setup email: {}", e.getMessage());
                    }
                }
                LOG.info("Root user created in realm '{}': email={}", realmId, email);
            } else if (response.getStatus() == 409) {
                LOG.info("Root user already exists (409) in realm '{}': email={}", realmId, email);
            } else {
                throw new IllegalStateException(
                        "Failed to create root user in realm '"
                                + realmId
                                + "': HTTP "
                                + response.getStatus());
            }
        } finally {
            response.close();
        }
    }

    /**
     * Ensure the platform realm exists with a root super-admin user.
     *
     * @param platformRealmId the platform realm name (e.g. {@code operio-platform})
     * @param rootEmail the root admin email
     * @param rootPassword the root admin password (from bootstrap key)
     * @return provision result containing realm ID and client secret
     */
    public ProvisionResult ensurePlatformRealm(
            String platformRealmId, String rootEmail, String rootPassword) {
        // Check if realm already exists
        try {
            adminClient.realm(platformRealmId).toRepresentation();
            LOG.info("Platform realm '{}' already exists", platformRealmId);
            // Get existing client secret
            RealmResource realm = adminClient.realm(platformRealmId);
            var clients = realm.clients().findByClientId(operioClientId);
            String secret = null;
            if (!clients.isEmpty()) {
                secret = realm.clients().get(clients.get(0).getId()).getSecret().getValue();
            }
            ensurePlatformRootUser(platformRealmId, rootEmail, rootPassword);
            return new ProvisionResult(platformRealmId, secret);
        } catch (jakarta.ws.rs.NotFoundException e) {
            // Realm doesn't exist — create it
            String clientSecret = provisionRealm(platformRealmId, "Operio Platform");
            ensurePlatformRootUser(platformRealmId, rootEmail, rootPassword);
            return new ProvisionResult(platformRealmId, clientSecret);
        }
    }

    private void ensurePlatformRootUser(String platformRealmId, String email, String password) {
        RealmResource realm = adminClient.realm(platformRealmId);

        List<UserRepresentation> existing = realm.users().searchByUsername("root", true);
        if (!existing.isEmpty()) {
            LOG.debug("Platform root user already exists in realm '{}'", platformRealmId);
            return;
        }

        UserRepresentation user = new UserRepresentation();
        user.setUsername("root");
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(true);

        var response = realm.users().create(user);
        try {
            if (response.getStatus() == 201) {
                String userId = extractUserId(response);
                if (userId != null && password != null) {
                    org.keycloak.representations.idm.CredentialRepresentation cred =
                            new org.keycloak.representations.idm.CredentialRepresentation();
                    cred.setType(
                            org.keycloak.representations.idm.CredentialRepresentation.PASSWORD);
                    cred.setValue(password);
                    cred.setTemporary(false);
                    realm.users().get(userId).resetPassword(cred);

                    // Assign super-admin role
                    try {
                        RoleRepresentation superAdmin =
                                realm.roles().get("super-admin").toRepresentation();
                        realm.users().get(userId).roles().realmLevel().add(List.of(superAdmin));
                    } catch (jakarta.ws.rs.NotFoundException nfe) {
                        // Create the role first
                        RoleRepresentation newRole = new RoleRepresentation();
                        newRole.setName("super-admin");
                        newRole.setDescription("Super administrator with full platform access");
                        realm.roles().create(newRole);
                        RoleRepresentation superAdmin =
                                realm.roles().get("super-admin").toRepresentation();
                        realm.users().get(userId).roles().realmLevel().add(List.of(superAdmin));
                    }
                }
                LOG.info("Platform root user created in realm '{}'", platformRealmId);
            } else if (response.getStatus() == 409) {
                LOG.debug("Platform root user already exists (409) in realm '{}'", platformRealmId);
            }
        } finally {
            response.close();
        }
    }

    private String extractUserId(jakarta.ws.rs.core.Response response) {
        String location = response.getHeaderString("Location");
        if (location != null && location.contains("/")) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        return null;
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
