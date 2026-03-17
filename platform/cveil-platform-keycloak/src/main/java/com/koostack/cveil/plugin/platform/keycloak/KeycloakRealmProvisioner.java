package com.koostack.cveil.plugin.platform.keycloak;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ManagementPermissionReference;
import org.keycloak.representations.idm.ManagementPermissionRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.ClientPolicyRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;
import org.keycloak.representations.idm.authorization.ScopePermissionRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions and deprovisions Keycloak realms for Cveil tenants.
 *
 * <p>Each tenant gets its own realm with:
 *
 * <ul>
 *   <li>A confidential {@code cveil-backend} client for token exchange
 *   <li>Default Cveil roles ({@code consent:read}, {@code consent:write}, {@code admin:read},
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
    private final String cveilClientId;
    private final String serverUrl;

    /** Result of provisioning a Keycloak realm. */
    public record ProvisionResult(String realmId, String clientSecret) {}

    public KeycloakRealmProvisioner(Keycloak adminClient, String cveilClientId, String serverUrl) {
        this.adminClient = adminClient;
        this.cveilClientId = cveilClientId;
        this.serverUrl = serverUrl;
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
     * @return the generated client secret for the cveil-backend client
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

        // Enable Keycloak Organizations on this realm
        realm.setOrganizationsEnabled(true);

        // Brute force protection
        realm.setBruteForceProtected(true);
        realm.setMaxFailureWaitSeconds(900);
        realm.setFailureFactor(5);

        adminClient.realms().create(realm);
        LOG.info("Realm '{}' created", tenantId);

        RealmResource realmResource = adminClient.realm(tenantId);

        // Create confidential client
        String clientSecret = createCveilClient(realmResource, tenantId);

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
     * Migrate an existing cveil-backend client to enable DPoP-bound access tokens.
     *
     * <p>This is an idempotent migration — safe to call multiple times. It reads the current client
     * attributes and adds {@code dpop.bound.access.tokens=true} if not already present.
     *
     * @param realmId the realm to migrate
     */
    public void migrateClientDpop(String realmId) {
        try {
            RealmResource realm = adminClient.realm(realmId);
            List<ClientRepresentation> clients = realm.clients().findByClientId(cveilClientId);
            if (clients.isEmpty()) {
                LOG.warn("No cveil client found in realm '{}' — skipping DPoP migration", realmId);
                return;
            }
            ClientRepresentation client = clients.get(0);
            Map<String, String> attrs = client.getAttributes();
            if (attrs != null && "true".equals(attrs.get("dpop.bound.access.tokens"))) {
                LOG.debug("DPoP already enabled for client in realm '{}'", realmId);
                return;
            }
            Map<String, String> updatedAttrs = new HashMap<>(attrs != null ? attrs : Map.of());
            updatedAttrs.put("dpop.bound.access.tokens", "true");
            client.setAttributes(updatedAttrs);
            realm.clients().get(client.getId()).update(client);
            LOG.info("DPoP enabled for cveil-backend client in realm '{}'", realmId);
        } catch (Exception e) {
            LOG.error("Failed to migrate DPoP for realm '{}': {}", realmId, e.getMessage(), e);
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
            // Ensure tenant-admin role is assigned even for existing users
            ensureAdminRole(realm, existing.get(0).getId());
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
                    ensureAdminRole(realm, userId);
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
     * @param platformRealmId the platform realm name (e.g. {@code cveil-platform})
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
            var clients = realm.clients().findByClientId(cveilClientId);
            String secret = null;
            if (!clients.isEmpty()) {
                secret = realm.clients().get(clients.get(0).getId()).getSecret().getValue();
            }
            ensurePlatformRootUser(platformRealmId, rootEmail, rootPassword);
            // Repair roles, client scope, and user assignments for existing realms
            repairRealm(platformRealmId);
            return new ProvisionResult(platformRealmId, secret);
        } catch (jakarta.ws.rs.NotFoundException e) {
            // Realm doesn't exist — create it
            String clientSecret = provisionRealm(platformRealmId, "Cveil Platform");
            ensurePlatformRootUser(platformRealmId, rootEmail, rootPassword);
            return new ProvisionResult(platformRealmId, clientSecret);
        }
    }

    /**
     * Configures SMTP settings on the given realm so Keycloak can send emails (invitations,
     * password resets, email verification).
     *
     * @param realmName the realm to configure
     * @param host SMTP server host (e.g. smtp.gmail.com)
     * @param port SMTP port (e.g. "587")
     * @param from sender email address
     * @param user SMTP auth username
     * @param password SMTP auth password (app password for Gmail)
     */
    public void configureRealmSmtp(
            String realmName, String host, String port, String from, String user, String password) {
        RealmResource realm = adminClient.realm(realmName);
        RealmRepresentation rep = realm.toRepresentation();

        Map<String, String> smtp = new HashMap<>();
        smtp.put("host", host);
        smtp.put("port", port);
        smtp.put("from", from);
        smtp.put("fromDisplayName", "Cveil");
        smtp.put("auth", "true");
        smtp.put("user", user);
        smtp.put("password", password);
        smtp.put("starttls", "true");
        smtp.put("ssl", "false");

        rep.setSmtpServer(smtp);
        realm.update(rep);
        LOG.info("SMTP configured on realm '{}' (host={}, from={})", realmName, host, from);
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

    private String createCveilClient(RealmResource realmResource, String tenantId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(cveilClientId);
        client.setName("Cveil Backend (" + tenantId + ")");
        client.setEnabled(true);
        client.setProtocol("openid-connect");
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setDirectAccessGrantsEnabled(true); // Resource Owner Password Credentials
        client.setStandardFlowEnabled(false);
        client.setFullScopeAllowed(true); // Include all realm roles in access token
        client.setAttributes(
                Map.of(
                        "access.token.lifespan",
                        String.valueOf(ACCESS_TOKEN_LIFESPAN_SECONDS),
                        "dpop.bound.access.tokens",
                        "true"));
        // offline_access is already a realm-level optional scope and will be
        // assigned to new clients automatically — no need to set it explicitly.

        realmResource.clients().create(client);

        // Retrieve the generated client secret
        List<ClientRepresentation> clients = realmResource.clients().findByClientId(cveilClientId);
        if (clients.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to find cveil client after creation in realm " + tenantId);
        }

        String internalId = clients.get(0).getId();
        String secret = realmResource.clients().get(internalId).getSecret().getValue();

        // Enable token-exchange permissions on the cveil client
        configureTokenExchangePermission(realmResource, internalId);

        // Add tenant_id protocol mapper so the user attribute appears in JWT access tokens
        addTenantIdProtocolMapper(realmResource, internalId);

        LOG.debug("Cveil client created in realm '{}', clientId='{}'", tenantId, cveilClientId);
        return secret;
    }

    private static final String TENANT_ID_MAPPER_NAME = "tenant_id";

    /**
     * Adds a protocol mapper to the cveil-backend client that maps the user attribute {@code
     * tenant_id} into the JWT access token as a {@code tenant_id} claim.
     */
    private void addTenantIdProtocolMapper(RealmResource realmResource, String clientInternalId) {
        try {
            ClientResource clientResource = realmResource.clients().get(clientInternalId);

            // Check if mapper already exists
            boolean exists =
                    clientResource.getProtocolMappers().getMappers().stream()
                            .anyMatch(m -> TENANT_ID_MAPPER_NAME.equals(m.getName()));
            if (exists) {
                return;
            }

            ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
            mapper.setName(TENANT_ID_MAPPER_NAME);
            mapper.setProtocol("openid-connect");
            mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");
            Map<String, String> config = new HashMap<>();
            config.put("user.attribute", "tenant_id");
            config.put("claim.name", "tenant_id");
            config.put("jsonType.label", "String");
            config.put("access.token.claim", "true");
            config.put("id.token.claim", "true");
            config.put("userinfo.token.claim", "true");
            mapper.setConfig(config);

            clientResource.getProtocolMappers().createMapper(mapper);
            LOG.debug("Added tenant_id protocol mapper to client '{}'", clientInternalId);
        } catch (Exception e) {
            LOG.warn("Failed to add tenant_id protocol mapper: {}", e.getMessage());
        }
    }

    /**
     * Configures fine-grained permissions so the cveil-backend client can perform token exchange
     * (offline token generation) on behalf of authenticated users. Also configures user
     * impersonation permission for direct naked impersonation (creating new offline sessions
     * without needing the user's access token).
     */
    private void configureTokenExchangePermission(
            RealmResource realmResource, String cveilClientInternalId) {
        try {
            ClientResource clientResource = realmResource.clients().get(cveilClientInternalId);

            // Enable management permissions — creates token-exchange scope in realm-management
            ManagementPermissionReference permRef =
                    clientResource.setPermissions(new ManagementPermissionRepresentation(true));

            String tokenExchangePermId = permRef.getScopePermissions().get("token-exchange");
            if (tokenExchangePermId == null) {
                LOG.warn("No token-exchange scope permission created; token exchange may not work");
                return;
            }

            // Find the realm-management client's authorization resource
            List<ClientRepresentation> rmClients =
                    realmResource.clients().findByClientId("realm-management");
            if (rmClients.isEmpty()) {
                LOG.warn(
                        "realm-management client not found; cannot configure token-exchange"
                                + " policy");
                return;
            }
            String rmInternalId = rmClients.get(0).getId();
            var authz = realmResource.clients().get(rmInternalId).authorization();

            // Create a client policy allowing cveil-backend to perform the exchange
            ClientPolicyRepresentation policy = new ClientPolicyRepresentation();
            policy.setName("cveil-token-exchange-policy");
            policy.setDescription(
                    "Allows cveil-backend to perform token exchange and impersonation");
            policy.setClients(Set.of(cveilClientInternalId));
            policy.setLogic(Logic.POSITIVE);
            authz.policies().client().create(policy);

            // Update the token-exchange scope permission to use our policy
            ScopePermissionRepresentation perm =
                    authz.permissions().scope().findById(tokenExchangePermId).toRepresentation();
            perm.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);
            perm.addPolicy(policy.getName());
            authz.permissions().scope().findById(tokenExchangePermId).update(perm);

            LOG.debug("Token-exchange permission configured for cveil client");

            // Enable user management permissions for impersonation (direct naked impersonation)
            configureImpersonationPermission(realmResource);
        } catch (Exception e) {
            LOG.warn(
                    "Failed to configure token-exchange permission (feature may not be enabled):"
                            + " {}",
                    e.getMessage());
        }
    }

    /**
     * Enables fine-grained user impersonation so the cveil-backend client can mint tokens on behalf
     * of any user via {@code requested_subject} token exchange. Uses the Keycloak admin REST API
     * directly because the Java admin client does not expose the users-management-permissions
     * endpoint.
     */
    private void configureImpersonationPermission(RealmResource realmResource) {
        try {
            String realmName = realmResource.toRepresentation().getRealm();
            String adminToken = adminClient.tokenManager().getAccessTokenString();
            HttpClient httpClient =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            // 1. Enable user management permissions via admin REST API
            String permUrl =
                    serverUrl + "/admin/realms/" + realmName + "/users-management-permissions";
            HttpResponse<String> permResp =
                    httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(permUrl))
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Content-Type", "application/json")
                                    .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":true}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            if (permResp.statusCode() != 200) {
                LOG.warn(
                        "Failed to enable user management permissions: HTTP {}, body={}",
                        permResp.statusCode(),
                        permResp.body());
                return;
            }

            // 2. Extract the impersonate scope permission ID from the JSON response
            String impersonatePermId = extractJsonField(permResp.body(), "impersonate");
            if (impersonatePermId == null || impersonatePermId.isBlank()) {
                LOG.warn("No impersonate scope permission returned; impersonation may not work");
                return;
            }

            // 3. Update the impersonate permission to include our token-exchange policy
            List<ClientRepresentation> rmClients =
                    realmResource.clients().findByClientId("realm-management");
            if (rmClients.isEmpty()) {
                LOG.warn("realm-management client not found; cannot configure impersonation");
                return;
            }
            String rmInternalId = rmClients.get(0).getId();
            var authz = realmResource.clients().get(rmInternalId).authorization();

            ScopePermissionRepresentation impersonatePerm =
                    authz.permissions().scope().findById(impersonatePermId).toRepresentation();
            impersonatePerm.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);
            impersonatePerm.addPolicy("cveil-token-exchange-policy");
            authz.permissions().scope().findById(impersonatePermId).update(impersonatePerm);

            LOG.debug(
                    "Impersonation permission configured for cveil client in realm '{}'",
                    realmName);
        } catch (Exception e) {
            LOG.warn("Failed to configure impersonation permission: {}", e.getMessage());
        }
    }

    /** Extracts a JSON string value by key (simple parser for flat objects). */
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private void createDefaultRoles(RealmResource realmResource) {
        RolesResource rolesResource = realmResource.roles();
        for (String roleName : DEFAULT_ROLES) {
            try {
                rolesResource.get(roleName).toRepresentation();
                LOG.debug("Role '{}' already exists, skipping", roleName);
            } catch (jakarta.ws.rs.NotFoundException e) {
                RoleRepresentation role = new RoleRepresentation();
                role.setName(roleName);
                role.setDescription("Cveil default role: " + roleName);
                rolesResource.create(role);
            }
        }
        LOG.debug("Ensured {} default roles exist", DEFAULT_ROLES.size());
    }

    private void ensureAdminRole(RealmResource realm, String userId) {
        try {
            RoleRepresentation adminRole = realm.roles().get("tenant-admin").toRepresentation();
            List<RoleRepresentation> current =
                    realm.users().get(userId).roles().realmLevel().listEffective();
            boolean hasRole = current.stream().anyMatch(r -> "tenant-admin".equals(r.getName()));
            if (!hasRole) {
                realm.users().get(userId).roles().realmLevel().add(List.of(adminRole));
                LOG.info("Assigned tenant-admin role to user {}", userId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to ensure tenant-admin role for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Repair an existing realm: ensure all default roles exist, the cveil-backend client has full
     * scope allowed so realm roles appear in JWT access tokens, and backfill tenant-admin for users
     * who were registered before role assignment was added.
     */
    public void repairRealm(String tenantId) {
        LOG.info("Repairing realm configuration for tenant '{}'", tenantId);
        RealmResource realmResource = adminClient.realm(tenantId);

        // Ensure Organizations feature is enabled on the realm
        RealmRepresentation realmRep = realmResource.toRepresentation();
        if (realmRep.isOrganizationsEnabled() == null || !realmRep.isOrganizationsEnabled()) {
            realmRep.setOrganizationsEnabled(true);
            realmResource.update(realmRep);
            LOG.info("Organizations feature enabled on realm '{}'", tenantId);
        }

        // Ensure all default roles exist
        createDefaultRoles(realmResource);

        // Ensure cveil-backend client has fullScopeAllowed
        List<ClientRepresentation> clients = realmResource.clients().findByClientId(cveilClientId);
        if (!clients.isEmpty()) {
            ClientRepresentation client = clients.get(0);
            if (!Boolean.TRUE.equals(client.isFullScopeAllowed())) {
                client.setFullScopeAllowed(true);
                realmResource.clients().get(client.getId()).update(client);
                LOG.info("Set fullScopeAllowed=true on client '{}'", cveilClientId);
            }
        }

        // Backfill: ensure every human user has at least tenant-admin.
        // Users registered before the role-assignment fix got zero custom roles.
        // Also backfill tenant_id user attribute for users registered before JWT mapping.
        List<UserRepresentation> allUsers = realmResource.users().list(0, 100);
        for (UserRepresentation user : allUsers) {
            if (user.getUsername() != null && user.getUsername().startsWith("service-account-")) {
                continue;
            }
            ensureAdminRole(realmResource, user.getId());
            backfillTenantIdAttribute(realmResource, user, tenantId);
        }

        // Ensure tenant_id protocol mapper exists on the cveil-backend client
        if (!clients.isEmpty()) {
            addTenantIdProtocolMapper(realmResource, clients.get(0).getId());
        }

        LOG.info("Realm repair complete for tenant '{}'", tenantId);
    }

    /**
     * Backfills the {@code tenant_id} user attribute for users registered before the JWT-embedded
     * tenant_id feature. For the shared "cveil" realm, derives tenant_id from the username; for
     * per-tenant realms, uses the tenantId directly.
     */
    private void backfillTenantIdAttribute(
            RealmResource realmResource, UserRepresentation user, String realmTenantId) {
        Map<String, List<String>> attrs = user.getAttributes();
        if (attrs != null && attrs.containsKey("tenant_id")) {
            return; // already set
        }

        // For the shared platform realm, derive tenant_id from username
        // For per-tenant realms, use the realm's tenant ID directly
        String derivedTenantId =
                "cveil".equals(realmTenantId) ? deriveTenantId(user.getUsername()) : realmTenantId;

        if (derivedTenantId == null || derivedTenantId.isBlank()) {
            return;
        }

        if (attrs == null) {
            attrs = new HashMap<>();
        }
        attrs.put("tenant_id", List.of(derivedTenantId));
        user.setAttributes(attrs);
        realmResource.users().get(user.getId()).update(user);
        LOG.info(
                "Backfilled tenant_id='{}' for user '{}' in realm '{}'",
                derivedTenantId,
                user.getUsername(),
                realmTenantId);
    }

    /**
     * Derives a tenant ID from a username using the same algorithm as {@code
     * TenantIdDeriver.derive()}: lowercase, replace non-alphanumeric with underscore, strip leading
     * digits, max 63 chars.
     */
    private static String deriveTenantId(String username) {
        if (username == null || username.isBlank()) return null;
        String id = username.toLowerCase().replaceAll("[^a-z0-9]", "_");
        id = id.replaceAll("^[0-9]+", "");
        if (id.isEmpty()) return null;
        return id.length() > 63 ? id.substring(0, 63) : id;
    }
}
