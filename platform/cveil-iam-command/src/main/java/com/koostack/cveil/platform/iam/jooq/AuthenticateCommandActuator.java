package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.common.keycloak.KeycloakAuthSupport;
import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.platform.events.UserAuthenticatedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "authenticate" command for platform IAM.
 *
 * <p>Performs the OIDC Resource Owner Password Credentials grant directly against Keycloak via
 * {@link KeycloakAuthSupport}, then publishes a {@link UserAuthenticatedEvent}.
 */
public class AuthenticateCommandActuator
        implements CommandActuator<AuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticateCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final KeycloakAuthSupport authSupport;

    private final EventService<?> eventService;

    public AuthenticateCommandActuator(
            EventService<?> eventService, KeycloakAuthSupport authSupport) {
        this.eventService = eventService;
        this.authSupport = authSupport;
    }

    /**
     * Command input for authenticating a platform user.
     *
     * @param realmId the Keycloak realm
     * @param email the user's email
     * @param password the user's password
     * @param dpopProof optional DPoP proof JWT from the client (forwarded to Keycloak)
     */
    public record AuthenticateCommand(
            String realmId, String email, String password, String dpopProof) {
        /** Convenience constructor without DPoP proof (backward compatible). */
        public AuthenticateCommand(String realmId, String email, String password) {
            this(realmId, email, password, null);
        }
    }

    @Override
    public AuthResult execute(AuthenticateCommand command) {
        AuthResult result =
                authSupport.authenticate(
                        command.realmId(),
                        command.email(),
                        command.password(),
                        command.dpopProof());

        // Publish domain event
        String userId = result.identity().subject();
        var payload = new UserAuthenticatedEvent(command.realmId(), userId, command.email());

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.USER_AUTHENTICATED,
                                key,
                                payload));

        LOG.info(
                "User authenticated: realmId={}, email={}, userId={}",
                command.realmId(),
                command.email(),
                userId);

        return result;
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
