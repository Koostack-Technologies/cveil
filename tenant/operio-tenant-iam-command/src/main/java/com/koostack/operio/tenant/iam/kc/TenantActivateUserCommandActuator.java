package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.tenant.events.TenantUserActivatedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.nio.charset.StandardCharsets;
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
 * <p>Re-enables the user in the tenant's Keycloak realm first, then publishes a {@link
 * TenantUserActivatedEvent}. If the Keycloak call fails the event is never recorded.
 */
public class TenantActivateUserCommandActuator
        implements Actuator<TenantActivateUserCommandActuator.ActivateUserCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantActivateUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final Keycloak adminClient;

    public TenantActivateUserCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for activating a suspended tenant user.
     *
     * @param tenantId the tenant identifier
     * @param userId the resolved user ID
     * @param emailOrUsername the original identifier (used for logging)
     */
    public record ActivateUserCommand(String tenantId, String userId, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, ActivateUserCommand command) {
        String realmId = toRealmId(command.tenantId());

        // Phase 2: Keycloak side-effect BEFORE publishing event
        if (adminClient != null) {
            RealmResource realm = adminClient.realm(realmId);
            String kcUserId = resolveKcUserId(realm, command.userId());
            var userResource = realm.users().get(kcUserId);
            var user = userResource.toRepresentation();
            user.setEnabled(true);
            userResource.update(user);
        }

        // Publish domain event
        var payload = new TenantUserActivatedEvent(command.tenantId(), command.userId());
        UUID aggregateId = deriveAggregateId(command.tenantId());
        AggregateKey key = new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(), TenantIamEventTypes.USER_ACTIVATED, key, payload));

        LOG.info(
                "Tenant user activated: tenantId={}, identifier={}",
                command.tenantId(),
                command.emailOrUsername());
        return null;
    }

    private String resolveKcUserId(RealmResource realm, String commandUserId) {
        List<UserRepresentation> users =
                realm.users().searchByAttributes("operio-user-id:" + commandUserId);
        if (!users.isEmpty()) return users.get(0).getId();
        throw new IllegalStateException(
                "User not found by operio-user-id attribute: " + commandUserId);
    }

    private static UUID deriveAggregateId(String tenantId) {
        return UUID.nameUUIDFromBytes(
                (USER_AGGREGATE_TYPE + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static String toRealmId(String tenantId) {
        return "t_" + tenantId;
    }
}
