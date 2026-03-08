package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.ServiceAccountRevokedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "revoke service account" command for platform IAM.
 *
 * <p>Disables the Keycloak client first, then publishes a {@link ServiceAccountRevokedEvent}. If
 * the Keycloak call fails the event is never recorded.
 */
public class RevokeServiceAccountCommandActuator
        implements CommandActuator<RevokeServiceAccountCommandActuator.RevokeServiceAccountCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RevokeServiceAccountCommandActuator.class);
    private static final String SERVICE_ACCOUNT_AGGREGATE_TYPE = "iam-service-account";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    public RevokeServiceAccountCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    /**
     * Command input for revoking a platform service account.
     *
     * @param realmId the Keycloak realm
     * @param clientId the Keycloak client ID to revoke
     */
    public record RevokeServiceAccountCommand(String realmId, String clientId) {}

    @Override
    public Void execute(RevokeServiceAccountCommand command) {
        // Phase 2: Keycloak side-effect before event
        RealmResource realm = adminClient.realm(command.realmId());
        List<ClientRepresentation> clients = realm.clients().findByClientId(command.clientId());
        if (!clients.isEmpty()) {
            String internalId = clients.get(0).getId();
            ClientRepresentation client = realm.clients().get(internalId).toRepresentation();
            client.setEnabled(false);
            realm.clients().get(internalId).update(client);
        } else {
            LOG.debug(
                    "Service account not found for revocation: realm={}, clientId={}",
                    command.realmId(),
                    command.clientId());
        }

        // Publish domain event
        var payload = new ServiceAccountRevokedEvent(command.realmId(), command.clientId());

        UUID aggregateId = deriveAggregateId(command.realmId(), command.clientId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        SERVICE_ACCOUNT_AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.SERVICE_ACCOUNT_REVOKED,
                        key,
                        payload));

        LOG.info(
                "Service account revoked: realmId={}, clientId={}",
                command.realmId(),
                command.clientId());
        return null;
    }

    private static UUID deriveAggregateId(String realmId, String clientId) {
        return UUID.nameUUIDFromBytes(
                (realmId + ":" + clientId).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
