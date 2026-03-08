package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.tenant.events.TenantTokenRefreshedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "refresh token" command for tenant IAM.
 *
 * <p>Performs the OIDC refresh-token grant directly against the tenant's Keycloak realm
 * via {@link KeycloakAuthSupport}, then publishes a {@link TenantTokenRefreshedEvent}.
 */
public class TenantRefreshTokenCommandActuator
        implements Actuator<TenantRefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantRefreshTokenCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final KeycloakAuthSupport authSupport;

    public TenantRefreshTokenCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for refreshing a tenant access token.
     *
     * @param tenantId     the tenant identifier (maps to Keycloak realm)
     * @param refreshToken the refresh token
     */
    public record RefreshTokenCommand(String tenantId, String refreshToken) {}

    @Override
    public AuthResult execute(EventService<?> publisher, RefreshTokenCommand command) {
        AuthResult result = authSupport.refreshToken(
                command.tenantId(), command.refreshToken());

        // Publish domain event
        String userId = result.identity().subject();
        var payload = new TenantTokenRefreshedEvent(command.tenantId(), userId);

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        publisher.publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(),
                        TenantIamEventTypes.TOKEN_REFRESHED,
                        key,
                        payload));

        LOG.info(
                "Tenant token refreshed: tenantId={}, userId={}",
                command.tenantId(),
                userId);

        return result;
    }
}
