package com.koostack.operio.tenant.iam.kc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.platform.iam.UserIdMapping;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.*;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant stream event handler that materializes tenant IAM domain events into Keycloak Admin API
 * calls.
 *
 * <p>Consumes events from the per-tenant Redis stream and projects them into the tenant's Keycloak
 * realm ({@code t_{tenantId}}). All operations are idempotent.
 */
public class TenantIamProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TenantIamProjectionHandler.class);

    private final Keycloak adminClient;
    private final ObjectMapper mapper;
    private final String operioClientId;

    public TenantIamProjectionHandler(Keycloak adminClient, String operioClientId) {
        this.adminClient = adminClient;
        this.operioClientId = operioClientId;
        this.mapper = new ObjectMapper();
    }

    /** Handle a tenant stream event. */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                switch (eventType) {
                    case TenantIamEventTypes.USER_REGISTERED -> handleUserRegistered(payload);
                    case TenantIamEventTypes.USER_SUSPENDED -> handleUserSuspended(payload);
                    case TenantIamEventTypes.USER_ACTIVATED -> handleUserActivated(payload);
                    case TenantIamEventTypes.SERVICE_ACCOUNT_CREATED ->
                            handleServiceAccountCreated(payload);
                    case TenantIamEventTypes.SERVICE_ACCOUNT_REVOKED ->
                            handleServiceAccountRevoked(payload);
                    case TenantIamEventTypes.ROLE_ASSIGNED -> handleRoleAssigned(payload);
                    case TenantIamEventTypes.ROLE_REMOVED -> handleRoleRemoved(payload);
                    default -> LOG.trace("Ignoring unhandled tenant event type: {}", eventType);
                }
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    LOG.error(
                            "Failed to project tenant event after {} attempts: type={}"
                                    + " messageId={}",
                            maxRetries,
                            eventType,
                            messageId,
                            e);
                    throw new RuntimeException(
                            "TenantIamProjectionHandler failed for " + eventType, e);
                }
                LOG.warn(
                        "Retry {}/{} for tenant event type={} messageId={}: {}",
                        attempt,
                        maxRetries,
                        eventType,
                        messageId,
                        e.getMessage());
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
    }

    private void handleUserRegistered(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String userId = node.has("userId") ? node.get("userId").asText() : null;
        String email = node.get("email").asText();
        String fullName = node.has("fullName") && !node.get("fullName").isNull()
                ? node.get("fullName").asText() : null;
        boolean temporaryPassword = node.has("temporaryPassword")
                && node.get("temporaryPassword").asBoolean();

        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if user already exists
        List<UserRepresentation> existing = realm.users().searchByEmail(email, true);
        if (!existing.isEmpty()) {
            LOG.debug("User already exists in realm '{}': email={}", realmId, email);
            return;
        }

        UserRepresentation user = new UserRepresentation();
        user.setEmail(email);
        user.setUsername(email);
        user.setEnabled(true);
        user.setEmailVerified(!temporaryPassword);

        if (fullName != null && !fullName.isBlank()) {
            String[] parts = fullName.trim().split("\\s+", 2);
            user.setFirstName(parts[0]);
            if (parts.length > 1) user.setLastName(parts[1]);
        }

        if (temporaryPassword) {
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));
        }

        var response = realm.users().create(user);
        try {
            if (response.getStatus() == 201) {
                String createdUserId = extractUserId(response);
                LOG.info("Tenant user created in Keycloak: realm={}, email={}, kcId={}, cmdId={}",
                        realmId, email, createdUserId, userId);

                // Cache the command → KC ID mapping
                if (createdUserId != null && userId != null) {
                    UserIdMapping.register(userId, createdUserId);
                }

                // Assign roles if specified
                if (createdUserId != null && node.has("roles") && node.get("roles").isArray()) {
                    for (var roleNode : node.get("roles")) {
                        assignRealmRole(realm, createdUserId, roleNode.asText());
                    }
                }

                // Send "set password" email if temporary
                if (temporaryPassword && createdUserId != null) {
                    try {
                        realm.users().get(createdUserId)
                                .executeActionsEmail(List.of("UPDATE_PASSWORD"));
                    } catch (Exception e) {
                        LOG.warn("Failed to send password setup email: {}", e.getMessage());
                    }
                }
            } else if (response.getStatus() == 409) {
                LOG.debug("User already exists (409) in realm '{}': email={}", realmId, email);
            } else {
                LOG.error("Failed to create tenant user in Keycloak: HTTP {}",
                        response.getStatus());
            }
        } finally {
            response.close();
        }
    }

    private void handleUserSuspended(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String userId = node.get("userId").asText();
        RealmResource realm = adminClient.realm(realmId);

        try {
            String kcUserId = resolveKcUserId(realm, userId);
            var userResource = realm.users().get(kcUserId);
            var user = userResource.toRepresentation();
            user.setEnabled(false);
            userResource.update(user);
            LOG.info("Tenant user suspended: realm={}, userId={}", realmId, userId);
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.warn("User not found for suspension: realm={}, userId={}", realmId, userId);
        }
    }

    private void handleUserActivated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String userId = node.get("userId").asText();
        RealmResource realm = adminClient.realm(realmId);

        try {
            String kcUserId = resolveKcUserId(realm, userId);
            var userResource = realm.users().get(kcUserId);
            var user = userResource.toRepresentation();
            user.setEnabled(true);
            userResource.update(user);
            LOG.info("Tenant user activated: realm={}, userId={}", realmId, userId);
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.warn("User not found for activation: realm={}, userId={}", realmId, userId);
        }
    }

    private void handleServiceAccountCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String clientId = node.get("clientId").asText();
        String name = node.has("name") && !node.get("name").isNull()
                ? node.get("name").asText() : clientId;

        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if client already exists
        List<ClientRepresentation> existing = realm.clients().findByClientId(clientId);
        if (!existing.isEmpty()) {
            LOG.debug("Service account already exists in realm '{}': clientId={}",
                    realmId, clientId);
            return;
        }

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setName(name);
        client.setEnabled(true);
        client.setProtocol("openid-connect");
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setDirectAccessGrantsEnabled(false);
        client.setStandardFlowEnabled(false);

        realm.clients().create(client);

        // Assign roles to service account user
        if (node.has("roles") && node.get("roles").isArray()) {
            List<ClientRepresentation> created = realm.clients().findByClientId(clientId);
            if (!created.isEmpty()) {
                String internalId = created.get(0).getId();
                UserRepresentation saUser = realm.clients().get(internalId)
                        .getServiceAccountUser();
                if (saUser != null) {
                    for (var roleNode : node.get("roles")) {
                        assignRealmRole(realm, saUser.getId(), roleNode.asText());
                    }
                }
            }
        }

        LOG.info("Tenant service account created: realm={}, clientId={}", realmId, clientId);
    }

    private void handleServiceAccountRevoked(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String clientId = node.get("clientId").asText();

        RealmResource realm = adminClient.realm(realmId);
        List<ClientRepresentation> clients = realm.clients().findByClientId(clientId);
        if (clients.isEmpty()) {
            LOG.debug("Service account not found for revocation: realm={}, clientId={}",
                    realmId, clientId);
            return;
        }

        String internalId = clients.get(0).getId();
        ClientRepresentation client = realm.clients().get(internalId).toRepresentation();
        client.setEnabled(false);
        realm.clients().get(internalId).update(client);
        LOG.info("Tenant service account disabled: realm={}, clientId={}", realmId, clientId);
    }

    private void handleRoleAssigned(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String userId = node.get("userId").asText();
        String role = node.get("role").asText();
        RealmResource realm = adminClient.realm(realmId);
        String kcUserId = resolveKcUserId(realm, userId);
        assignRealmRole(realm, kcUserId, role);
        LOG.info("Tenant role assigned: realm={}, userId={}, role={}", realmId, userId, role);
    }

    private void handleRoleRemoved(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String realmId = toRealmId(tenantId);
        String userId = node.get("userId").asText();
        String role = node.get("role").asText();

        try {
            RealmResource realm = adminClient.realm(realmId);
            String kcUserId = resolveKcUserId(realm, userId);
            RolesResource rolesResource = realm.roles();
            RoleRepresentation roleRep = rolesResource.get(role).toRepresentation();
            realm.users().get(kcUserId).roles().realmLevel().remove(List.of(roleRep));
            LOG.info("Tenant role removed: realm={}, userId={}, role={}", realmId, userId, role);
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.debug("Role or user not found for removal: realm={}, userId={}, role={}",
                    realmId, userId, role);
        }
    }

    /**
     * Resolves a command-side userId to the actual Keycloak user ID.
     * KC 26+ ignores UserRepresentation.setId() during creation, so the KC-assigned ID
     * differs from the command UUID. We store the command UUID as a user attribute
     * ({@code operio-user-id}) and fall back to attribute search when direct lookup fails.
     */
    private String resolveKcUserId(RealmResource realm, String commandUserId) {
        String cached = UserIdMapping.toKcId(commandUserId);
        if (cached != null) return cached;
        try {
            realm.users().get(commandUserId).toRepresentation();
            return commandUserId;
        } catch (jakarta.ws.rs.NotFoundException e) {
            throw new jakarta.ws.rs.NotFoundException(
                    "User not found by ID or cache: " + commandUserId);
        }
    }

    private void assignRealmRole(RealmResource realm, String userId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by tenant IAM projection");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        }
    }

    private String extractUserId(jakarta.ws.rs.core.Response response) {
        String location = response.getHeaderString("Location");
        if (location != null && location.contains("/")) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        return null;
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
