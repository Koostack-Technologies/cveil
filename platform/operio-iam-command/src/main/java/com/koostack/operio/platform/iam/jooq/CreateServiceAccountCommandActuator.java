package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.ServiceAccountCreatedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
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
 * Actuator that handles the "create service account" command for platform IAM.
 *
 * <p>Creates the confidential client in Keycloak first (idempotent), retrieves the client secret,
 * assigns roles, then publishes a {@link ServiceAccountCreatedEvent}. If Keycloak fails the event
 * is never recorded.
 */
public class CreateServiceAccountCommandActuator
        implements Actuator<
                CreateServiceAccountCommandActuator.CreateServiceAccountCommand,
                CreateServiceAccountCommandActuator.CreateServiceAccountResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(CreateServiceAccountCommandActuator.class);
    private static final String SERVICE_ACCOUNT_AGGREGATE_TYPE = "iam-service-account";

    private final Keycloak adminClient;

    public CreateServiceAccountCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for creating a platform service account.
     *
     * @param realmId the Keycloak realm
     * @param name human-readable name for the service account
     * @param roles roles to assign to the service account (nullable — defaults to empty)
     */
    public record CreateServiceAccountCommand(String realmId, String name, Set<String> roles) {}

    /**
     * Result of creating a service account.
     *
     * @param clientId the Keycloak client ID
     * @param clientSecret the generated client secret
     * @param realmId the realm the service account was created in
     */
    public record CreateServiceAccountResult(
            String clientId, String clientSecret, String realmId) {}

    @Override
    public CreateServiceAccountResult execute(
            EventService<?> publisher, CreateServiceAccountCommand command) {
        if (command.realmId() == null || command.realmId().isBlank()) {
            throw new IllegalArgumentException("realmId is required");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        String clientId = "svc-" + command.name().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        Set<String> roles = command.roles() != null ? command.roles() : Set.of();

        // Phase 2: Keycloak side-effect before event
        String clientSecret =
                createKeycloakServiceAccount(command.realmId(), clientId, command.name(), roles);

        // Publish domain event
        var payload =
                new ServiceAccountCreatedEvent(command.realmId(), clientId, command.name(), roles);

        UUID aggregateId = deriveAggregateId(command.realmId(), clientId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        SERVICE_ACCOUNT_AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.SERVICE_ACCOUNT_CREATED,
                        key,
                        payload));

        LOG.info("Service account created: realmId={}, clientId={}", command.realmId(), clientId);

        return new CreateServiceAccountResult(clientId, clientSecret, command.realmId());
    }

    private String createKeycloakServiceAccount(
            String realmId, String clientId, String name, Set<String> roles) {
        RealmResource realm = adminClient.realm(realmId);

        // Idempotent: check if client already exists
        List<ClientRepresentation> existing = realm.clients().findByClientId(clientId);
        if (!existing.isEmpty()) {
            LOG.debug(
                    "Service account already exists in realm '{}': clientId={}", realmId, clientId);
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
            throw new IllegalStateException("Keycloak client created but not found: " + clientId);
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
            newRole.setDescription("Auto-created by IAM command");
            realm.roles().create(newRole);
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
        }
    }

    private static UUID deriveAggregateId(String realmId, String clientId) {
        return UUID.nameUUIDFromBytes(
                (realmId + ":" + clientId).getBytes(StandardCharsets.UTF_8));
    }
}
