package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.UserAppProfileUpdatedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles updates to app-specific user profile fields (display name, avatar, locale,
 * timezone). These fields live only in the local IAM mirror — Keycloak is not involved.
 *
 * <p>Publishes a {@link UserAppProfileUpdatedEvent} which the projection handler materializes into
 * the {@code cveiliam.iam_user} table.
 */
public class UpdateUserAppProfileCommandActuator
        implements CommandActuator<
                UpdateUserAppProfileCommandActuator.UpdateUserAppProfileCommand,
                UpdateUserAppProfileCommandActuator.UpdateUserAppProfileResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(UpdateUserAppProfileCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final EventService<?> eventService;

    public UpdateUserAppProfileCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    /**
     * Command input for updating app-specific profile fields.
     *
     * @param userId the Keycloak user ID (JWT sub claim)
     * @param displayName app display name override (null = no change)
     * @param avatarUrl avatar URL (null = no change)
     * @param locale preferred locale (null = no change)
     * @param timezone preferred timezone (null = no change)
     */
    public record UpdateUserAppProfileCommand(
            String userId, String displayName, String avatarUrl, String locale, String timezone) {}

    public record UpdateUserAppProfileResult(
            String userId, String displayName, String avatarUrl, String locale, String timezone) {}

    @Override
    public UpdateUserAppProfileResult execute(UpdateUserAppProfileCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (command.displayName() == null
                && command.avatarUrl() == null
                && command.locale() == null
                && command.timezone() == null) {
            throw new IllegalArgumentException("At least one profile field must be provided");
        }

        var payload =
                new UserAppProfileUpdatedEvent(
                        command.userId(),
                        command.displayName(),
                        command.avatarUrl(),
                        command.locale(),
                        command.timezone());

        UUID aggregateId = UUID.fromString(command.userId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.USER_APP_PROFILE_UPDATED,
                                key,
                                payload));

        LOG.info("User app profile updated: userId={}", command.userId());

        return new UpdateUserAppProfileResult(
                command.userId(),
                command.displayName(),
                command.avatarUrl(),
                command.locale(),
                command.timezone());
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
