package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.platform.events.TokenRefreshedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "refresh token" command for platform IAM.
 *
 * <p>Performs the OIDC refresh-token grant directly against Keycloak
 * via {@link KeycloakAuthSupport}, then publishes a {@link TokenRefreshedEvent}.
 */
public class RefreshTokenCommandActuator
        implements Actuator<RefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final KeycloakAuthSupport authSupport;

    public RefreshTokenCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for refreshing a platform access token.
     *
     * @param realmId      the Keycloak realm
     * @param refreshToken the refresh token
     */
    public record RefreshTokenCommand(String realmId, String refreshToken) {}

    @Override
    public AuthResult execute(EventService<?> publisher, RefreshTokenCommand command) {
        AuthResult result = authSupport.refreshToken(
                command.realmId(), command.refreshToken());

        // Publish domain event
        String userId = result.identity().subject();
        var payload = new TokenRefreshedEvent(command.realmId(), userId);

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        IamEventTypes.TOKEN_REFRESHED,
                        key,
                        payload));

        LOG.info(
                "Token refreshed: realmId={}, userId={}",
                command.realmId(),
                userId);

        return result;
    }
}
