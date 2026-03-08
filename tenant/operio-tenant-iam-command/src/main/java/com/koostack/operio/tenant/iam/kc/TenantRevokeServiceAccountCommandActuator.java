package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantServiceAccountRevokedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "revoke service account" command for tenant IAM.
 *
 * <p>Disables the Keycloak client in the tenant's realm first, then publishes a {@link
 * TenantServiceAccountRevokedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class TenantRevokeServiceAccountCommandActuator
        implements Actuator<
                TenantRevokeServiceAccountCommandActuator.RevokeServiceAccountCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantRevokeServiceAccountCommandActuator.class);
    private static final String SERVICE_ACCOUNT_AGGREGATE_TYPE = "tenant-iam-service-account";

    private final Keycloak adminClient;

    public TenantRevokeServiceAccountCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for revoking a tenant service account.
     *
     * @param tenantId the tenant identifier
     * @param clientId the Keycloak client ID to revoke
     */
    public record RevokeServiceAccountCommand(String tenantId, String clientId) {}

    @Override
    public Void execute(EventService<?> publisher, RevokeServiceAccountCommand command) {
        String realmId = toRealmId(command.tenantId());

        // Phase 2: Keycloak side-effect BEFORE publishing event
        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            List<ClientRepresentation> clients = realm.clients().findByClientId(command.clientId());
            if (!clients.isEmpty()) {
                String internalId = clients.get(0).getId();
                ClientRepresentation client = realm.clients().get(internalId).toRepresentation();
                client.setEnabled(false);
                realm.clients().get(internalId).update(client);
            } else {
                LOG.debug(
                        "Service account not found for revocation: realm={}, clientId={}",
                        realmId,
                        command.clientId());
            }
        }

        // Publish domain event
        var payload = new TenantServiceAccountRevokedEvent(command.tenantId(), command.clientId());

        UUID aggregateId = deriveAggregateId(realmId, command.clientId());
        AggregateKey key =
                new AggregateKey(aggregateId, SERVICE_ACCOUNT_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(),
                        TenantIamEventTypes.SERVICE_ACCOUNT_REVOKED,
                        key,
                        payload));

        LOG.info(
                "Tenant service account revoked: tenantId={}, clientId={}",
                command.tenantId(),
                command.clientId());
        return null;
    }

    private static UUID deriveAggregateId(String realmId, String clientId) {
        return UUID.nameUUIDFromBytes(
                (realmId + ":" + clientId).getBytes(StandardCharsets.UTF_8));
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
