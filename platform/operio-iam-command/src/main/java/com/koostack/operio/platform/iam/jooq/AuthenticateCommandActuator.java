package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.platform.events.UserAuthenticatedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "authenticate" command for platform IAM.
 *
 * <p>Performs the OIDC Resource Owner Password Credentials grant directly against Keycloak
 * via {@link KeycloakAuthSupport}, then publishes a {@link UserAuthenticatedEvent}.
 */
public class AuthenticateCommandActuator
        implements Actuator<AuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticateCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final KeycloakAuthSupport authSupport;

    public AuthenticateCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for authenticating a platform user.
     *
     * @param realmId  the Keycloak realm
     * @param email    the user's email
     * @param password the user's password
     */
    public record AuthenticateCommand(String realmId, String email, String password) {}

    @Override
    public AuthResult execute(EventService<?> publisher, AuthenticateCommand command) {
        AuthResult result = authSupport.authenticate(
                command.realmId(), command.email(), command.password());

        // Publish domain event
        String userId = result.identity().subject();
        var payload = new UserAuthenticatedEvent(
                command.realmId(), userId, command.email());

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
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
}
