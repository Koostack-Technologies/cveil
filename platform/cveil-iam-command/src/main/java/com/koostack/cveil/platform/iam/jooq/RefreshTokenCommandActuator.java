package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.common.keycloak.KeycloakAuthSupport;
import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.core.platform.events.TokenRefreshedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "refresh token" command for platform IAM.
 *
 * <p>Performs the OIDC refresh-token grant directly against Keycloak via {@link
 * KeycloakAuthSupport}, then publishes a {@link TokenRefreshedEvent}.
 */
public class RefreshTokenCommandActuator
        implements CommandActuator<RefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";

    private final KeycloakAuthSupport authSupport;

    private final EventService<?> eventService;

    public RefreshTokenCommandActuator(
            EventService<?> eventService, KeycloakAuthSupport authSupport) {
        this.eventService = eventService;
        this.authSupport = authSupport;
    }

    /**
     * Command input for refreshing a platform access token.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token
     * @param dpopProof optional DPoP proof JWT from the client (forwarded to Keycloak)
     */
    public record RefreshTokenCommand(String realmId, String refreshToken, String dpopProof) {
        /** Convenience constructor without DPoP proof (backward compatible). */
        public RefreshTokenCommand(String realmId, String refreshToken) {
            this(realmId, refreshToken, null);
        }
    }

    @Override
    public AuthResult execute(RefreshTokenCommand command) {
        AuthResult result =
                authSupport.refreshToken(
                        command.realmId(), command.refreshToken(), command.dpopProof());

        // Publish domain event
        String userId = result.identity().subject();
        var payload = new TokenRefreshedEvent(command.realmId(), userId);

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.TOKEN_REFRESHED,
                                key,
                                payload));

        LOG.info("Token refreshed: realmId={}, userId={}", command.realmId(), userId);

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
