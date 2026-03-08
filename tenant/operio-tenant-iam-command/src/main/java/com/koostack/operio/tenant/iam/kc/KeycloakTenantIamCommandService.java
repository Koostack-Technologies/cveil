package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.*;
import com.koostack.operio.core.tenant.iam.TenantIamCommandService;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak-native tenant IAM command service.
 *
 * <p>Publishes domain events for tenant user/service-account operations to the per-tenant event
 * store ({@code t_{tenantId}_event}). The tenant IAM projection handler translates these events
 * into Keycloak Admin API calls.
 */
public class KeycloakTenantIamCommandService
        implements TenantIamCommandService<KeycloakTenantIamCommandConfig> {

    private static final Logger LOG =
            LoggerFactory.getLogger(KeycloakTenantIamCommandService.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";
    private static final String SERVICE_ACCOUNT_AGGREGATE_TYPE = "tenant-iam-service-account";

    private EventService<?> eventService;
    private TenantIamQueryService<?> queryService;

    /** Set the query service for email/username → UUID resolution. */
    public void setQueryService(TenantIamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "keycloak-tenant-iam-command";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<KeycloakTenantIamCommandConfig> getConfigurationType() {
        return KeycloakTenantIamCommandConfig.class;
    }

    @Override
    public void configure(KeycloakTenantIamCommandConfig config) {
        this.eventService = config.eventService();
        LOG.info("KeycloakTenantIamCommandService configured");
    }

    @Override
    public boolean isReady() {
        return eventService != null;
    }

    // -- User operations ------------------------------------------------------

    @Override
    public RegisterUserResult registerUser(
            String tenantId,
            String username,
            String email,
            String fullName,
            Set<String> roles,
            boolean temporaryPassword) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        String realmId = toRealmId(tenantId);
        String userId = UUID.randomUUID().toString();
        var payload =
                new TenantUserRegisteredEvent(
                        tenantId,
                        userId,
                        username,
                        email,
                        fullName,
                        roles != null ? roles : Set.of(),
                        temporaryPassword);
        publishEvent(TenantIamEventTypes.USER_REGISTERED, tenantId, USER_AGGREGATE_TYPE, payload);

        LOG.info(
                "Tenant user registration event published: tenantId={}, username={}, email={}",
                tenantId,
                username,
                email);
        return new RegisterUserResult(username, email, realmId);
    }

    @Override
    public void suspendUser(String tenantId, String emailOrUsername) {
        String userId = requireUserId(tenantId, emailOrUsername);
        var payload = new TenantUserSuspendedEvent(tenantId, userId);
        publishEvent(TenantIamEventTypes.USER_SUSPENDED, tenantId, USER_AGGREGATE_TYPE, payload);
        LOG.info("Tenant user suspended: tenantId={}, identifier={}", tenantId, emailOrUsername);
    }

    @Override
    public void activateUser(String tenantId, String emailOrUsername) {
        String userId = requireUserId(tenantId, emailOrUsername);
        var payload = new TenantUserActivatedEvent(tenantId, userId);
        publishEvent(TenantIamEventTypes.USER_ACTIVATED, tenantId, USER_AGGREGATE_TYPE, payload);
        LOG.info("Tenant user activated: tenantId={}, identifier={}", tenantId, emailOrUsername);
    }

    // -- Service account operations -------------------------------------------

    @Override
    public CreateServiceAccountResult createServiceAccount(
            String tenantId, String name, Set<String> roles) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        String realmId = toRealmId(tenantId);
        String clientId = "svc-" + name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        var payload =
                new TenantServiceAccountCreatedEvent(
                        tenantId, clientId, name, roles != null ? roles : Set.of());
        publishEvent(
                TenantIamEventTypes.SERVICE_ACCOUNT_CREATED,
                tenantId,
                SERVICE_ACCOUNT_AGGREGATE_TYPE,
                payload);

        LOG.info(
                "Tenant service account creation event published: tenantId={}, clientId={}",
                tenantId,
                clientId);
        return new CreateServiceAccountResult(clientId, "pending-projection", realmId);
    }

    @Override
    public void revokeServiceAccount(String tenantId, String clientId) {
        var payload = new TenantServiceAccountRevokedEvent(tenantId, clientId);
        publishEvent(
                TenantIamEventTypes.SERVICE_ACCOUNT_REVOKED,
                tenantId,
                SERVICE_ACCOUNT_AGGREGATE_TYPE,
                payload);
        LOG.info("Tenant service account revoked: tenantId={}, clientId={}", tenantId, clientId);
    }

    // -- Role operations ------------------------------------------------------

    @Override
    public void assignRole(String tenantId, String emailOrUsername, String role) {
        String userId = requireUserId(tenantId, emailOrUsername);
        var payload = new TenantRoleAssignedEvent(tenantId, userId, role);
        publishEvent(TenantIamEventTypes.ROLE_ASSIGNED, tenantId, USER_AGGREGATE_TYPE, payload);
        LOG.info(
                "Tenant role assigned: tenantId={}, identifier={}, role={}",
                tenantId,
                emailOrUsername,
                role);
    }

    @Override
    public void removeRole(String tenantId, String emailOrUsername, String role) {
        String userId = requireUserId(tenantId, emailOrUsername);
        var payload = new TenantRoleRemovedEvent(tenantId, userId, role);
        publishEvent(TenantIamEventTypes.ROLE_REMOVED, tenantId, USER_AGGREGATE_TYPE, payload);
        LOG.info(
                "Tenant role removed: tenantId={}, identifier={}, role={}",
                tenantId,
                emailOrUsername,
                role);
    }

    // -- Internal helpers -----------------------------------------------------

    private String requireUserId(String tenantId, String emailOrUsername) {
        if (queryService == null) {
            throw new IllegalStateException(
                    "queryService not configured — call setQueryService() after wiring");
        }
        String userId = queryService.resolveUserId(tenantId, emailOrUsername);
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User not found: " + emailOrUsername + " in tenant " + tenantId);
        }
        return userId;
    }

    private void publishEvent(
            String eventType, String tenantId, String aggregateType, DomainEvent payload) {
        UUID aggregateId = deriveAggregateId(tenantId, aggregateType);
        AggregateKey key = new AggregateKey(aggregateId, aggregateType, tenantId);
        eventService.publishEvent(PublishDomainEventInput.of(tenantId, eventType, key, payload));
    }

    private static UUID deriveAggregateId(String tenantId, String aggregateType) {
        return UUID.nameUUIDFromBytes(
                (aggregateType + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
