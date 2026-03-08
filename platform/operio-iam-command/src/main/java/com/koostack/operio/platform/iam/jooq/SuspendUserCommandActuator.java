package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.UserSuspendedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "suspend user" command for platform IAM.
 *
 * <p>Disables the user in Keycloak first, then publishes a {@link UserSuspendedEvent}. If the
 * Keycloak call fails the event is never recorded.
 */
public class SuspendUserCommandActuator
        implements Actuator<SuspendUserCommandActuator.SuspendUserCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(SuspendUserCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final Keycloak adminClient;

    public SuspendUserCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }

    /**
     * Command input for suspending a platform user.
     *
     * @param realmId the Keycloak realm
     * @param userId the resolved user ID
     * @param emailOrUsername the original identifier (used for logging)
     */
    public record SuspendUserCommand(String realmId, String userId, String emailOrUsername) {}

    @Override
    public Void execute(EventService<?> publisher, SuspendUserCommand command) {
        // Phase 2: Keycloak side-effect before event
        RealmResource realm = adminClient.realm(command.realmId());
        String kcUserId = resolveKcUserId(realm, command.userId());
        var userResource = realm.users().get(kcUserId);
        var user = userResource.toRepresentation();
        user.setEnabled(false);
        userResource.update(user);

        // Publish domain event
        var payload = new UserSuspendedEvent(command.realmId(), command.userId());
        UUID aggregateId = deriveAggregateId(command.realmId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.USER_SUSPENDED,
                        key,
                        payload));

        LOG.info(
                "User suspended: realmId={}, identifier={}, kcId={}",
                command.realmId(),
                command.emailOrUsername(),
                kcUserId);
        return null;
    }

    private String resolveKcUserId(RealmResource realm, String commandUserId) {
        List<UserRepresentation> users =
                realm.users().searchByAttributes("operio-user-id:" + commandUserId);
        if (!users.isEmpty()) return users.get(0).getId();
        throw new IllegalStateException(
                "User not found by operio-user-id attribute: " + commandUserId);
    }

    private static UUID deriveAggregateId(String realmId) {
        return UUID.nameUUIDFromBytes(
                (USER_AGGREGATE_TYPE + ":" + realmId).getBytes(StandardCharsets.UTF_8));
    }
}
