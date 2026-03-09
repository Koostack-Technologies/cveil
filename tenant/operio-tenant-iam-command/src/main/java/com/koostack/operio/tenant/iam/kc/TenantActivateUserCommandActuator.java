package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantUserActivatedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "activate user" command for tenant IAM.
 *
 * <p>Resolves the Keycloak user from {@code emailOrUsername}, re-enables them in the tenant's
 * Keycloak realm, then publishes a {@link TenantUserActivatedEvent}.
 */
public class TenantActivateUserCommandActuator
        implements CommandActuator<TenantActivateUserCommandActuator.ActivateUserCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantActivateUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final Keycloak adminClient;

    private final EventService<?> eventService;

    public TenantActivateUserCommandActuator(EventService<?> eventService, Keycloak adminClient) {
        this.eventService = eventService;
        this.adminClient = adminClient;
    }

    /**
     * Command input for activating a suspended tenant user.
     *
     * @param tenantId the tenant identifier
     * @param emailOrUsername the user's email or username
     */
    public record ActivateUserCommand(String tenantId, String emailOrUsername) {}

    @Override
    public Void execute(ActivateUserCommand command) {
        String realmId = toRealmId(command.tenantId());
        String kcUserId = command.emailOrUsername(); // fallback if no admin client

        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            UserRepresentation kcUser = resolveKeycloakUser(realm, command.emailOrUsername());
            kcUserId = kcUser.getId();

            kcUser.setEnabled(true);
            realm.users().get(kcUserId).update(kcUser);
        }

        // Publish domain event — Keycloak user ID is the aggregate identity
        var payload = new TenantUserActivatedEvent(command.tenantId(), kcUserId);
        UUID aggregateId = toAggregateId(kcUserId);
        AggregateKey key = new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                command.tenantId(),
                                TenantIamEventTypes.USER_ACTIVATED,
                                key,
                                payload));

        LOG.info(
                "Tenant user activated: tenantId={}, identifier={}",
                command.tenantId(),
                command.emailOrUsername());
        return null;
    }

    private UserRepresentation resolveKeycloakUser(RealmResource realm, String emailOrUsername) {
        List<UserRepresentation> users = realm.users().searchByEmail(emailOrUsername, true);
        if (users.isEmpty()) {
            users = realm.users().searchByUsername(emailOrUsername, true);
        }
        if (users.isEmpty()) {
            throw new IllegalArgumentException("User not found: " + emailOrUsername);
        }
        return users.get(0);
    }

    private static UUID toAggregateId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
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
