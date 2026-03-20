package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.EmailVerificationSentEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import com.koostack.cveil.plugin.platform.keycloak.KeycloakIdpProvider;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that triggers a VERIFY_EMAIL action email via Keycloak.
 *
 * <p>Used for resending the verification email after initial registration. The initial verification
 * email is sent as part of the registration flow in {@link RegisterUserCommandActuator}.
 */
public class SendVerificationEmailCommandActuator
        implements CommandActuator<
                SendVerificationEmailCommandActuator.SendVerificationEmailCommand,
                SendVerificationEmailCommandActuator.SendVerificationEmailResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(SendVerificationEmailCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final EventService<?> eventService;
    private final KeycloakIdpProvider idpProvider;

    public SendVerificationEmailCommandActuator(
            EventService<?> eventService, KeycloakIdpProvider idpProvider) {
        this.eventService = eventService;
        this.idpProvider = idpProvider;
    }

    /**
     * Command input for sending a verification email.
     *
     * @param realmId the Keycloak realm
     * @param userId the Keycloak user ID
     * @param email the user's email (for event payload)
     */
    public record SendVerificationEmailCommand(String realmId, String userId, String email) {}

    /**
     * Result of sending a verification email.
     *
     * @param sent {@code true} if the email was sent successfully
     */
    public record SendVerificationEmailResult(boolean sent) {}

    @Override
    public SendVerificationEmailResult execute(SendVerificationEmailCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }

        try {
            idpProvider.sendVerificationEmail(command.userId());
        } catch (Exception e) {
            LOG.warn(
                    "Failed to send verification email for user '{}': {}",
                    command.userId(),
                    e.getMessage());
            return new SendVerificationEmailResult(false);
        }

        // Publish domain event
        UUID aggregateId = UUID.fromString(command.userId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new EmailVerificationSentEvent(
                        command.realmId(), command.userId(), command.email());

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.EMAIL_VERIFICATION_SENT,
                                key,
                                payload));

        LOG.info("Verification email sent: userId={}, email={}", command.userId(), command.email());
        return new SendVerificationEmailResult(true);
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
