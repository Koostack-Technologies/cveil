package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantServiceAccountCreatedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "create service account" command for tenant IAM.
 *
 * <p>Creates the confidential client in the tenant's Keycloak realm first (idempotent), retrieves
 * the client secret, assigns roles, then publishes a {@link TenantServiceAccountCreatedEvent}.
 * If Keycloak fails the event is never recorded.
 */
public class TenantCreateServiceAccountCommandActuator
        implements Actuator<
                TenantCreateServiceAccountCommandActuator.CreateServiceAccountCommand,
                TenantCreateServiceAccountCommandActuator.CreateServiceAccountResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantCreateServiceAccountCommandActuator.class);
    private static final String SERVICE_ACCOUNT_AGGREGATE_TYPE = "tenant-iam-service-account";

    private final Keycloak adminClient;

    public TenantCreateServiceAccountCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for creating a tenant service account.
     *
     * @param tenantId the tenant identifier
     * @param name human-readable name for the service account
     * @param roles roles to assign to the service account (nullable — defaults to empty)
     */
    public record CreateServiceAccountCommand(
            String tenantId, String name, Set<String> roles) {}

    /**
     * Result of creating a tenant service account.
     *
     * @param clientId the Keycloak client ID
     * @param clientSecret the generated client secret (or "pending-projection" if no KC client)
     * @param realmId the realm the service account was created in
     */
    public record CreateServiceAccountResult(
            String clientId, String clientSecret, String realmId) {}

    @Override
    public CreateServiceAccountResult execute(
            EventService<?> publisher, CreateServiceAccountCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        String realmId = toRealmId(command.tenantId());
        String clientId = "svc-" + command.name().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        Set<String> roles = command.roles() != null ? command.roles() : Set.of();

        // Phase 2: Keycloak side-effect BEFORE publishing event
        String clientSecret = "pending-projection";
        if (adminClient != null) {
            clientSecret = createKeycloakServiceAccount(realmId, clientId, command.name(), roles);
        }

        // Publish domain event (only reached if KC succeeded)
        var payload = new TenantServiceAccountCreatedEvent(
                command.tenantId(), clientId, command.name(), roles);

        UUID aggregateId = deriveAggregateId(command.tenantId());
        AggregateKey key = new AggregateKey(
                aggregateId, SERVICE_ACCOUNT_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(),
                        TenantIamEventTypes.SERVICE_ACCOUNT_CREATED,
                        key,
                        payload));

        LOG.info("Tenant service account created: tenantId={}, clientId={}",
                command.tenantId(), clientId);

        return new CreateServiceAccountResult(clientId, clientSecret, realmId);
    }

    private String createKeycloakServiceAccount(
            String realmId, String clientId, String name, Set<String> roles) {
        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if client already exists
        List<ClientRepresentation> existing = realm.clients().findByClientId(clientId);
        if (!existing.isEmpty()) {
            LOG.debug("Service account already exists in realm '{}': clientId={}",
                    realmId, clientId);
            String internalId = existing.get(0).getId();
            CredentialRepresentation secret = realm.clients().get(internalId).getSecret();
            return secret != null ? secret.getValue() : "existing";
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

        // Retrieve the created client to get its internal ID and secret
        List<ClientRepresentation> created = realm.clients().findByClientId(clientId);
        if (created.isEmpty()) {
            throw new IllegalStateException(
                    "Keycloak client created but not found: " + clientId);
        }
        String internalId = created.get(0).getId();
        CredentialRepresentation secret = realm.clients().get(internalId).getSecret();
        String clientSecret = secret != null ? secret.getValue() : null;

        // Assign roles to service account user
        UserRepresentation saUser = realm.clients().get(internalId).getServiceAccountUser();
        if (saUser != null && !roles.isEmpty()) {
            for (String roleName : roles) {
                assignRealmRole(realm, saUser.getId(), roleName);
            }
        }

        return clientSecret;
    }

    private void assignRealmRole(RealmResource realm, String userId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription("Auto-created by tenant IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        }
    }

    private static UUID deriveAggregateId(String tenantId) {
        return UUID.nameUUIDFromBytes(
                (SERVICE_ACCOUNT_AGGREGATE_TYPE + ":" + tenantId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}

