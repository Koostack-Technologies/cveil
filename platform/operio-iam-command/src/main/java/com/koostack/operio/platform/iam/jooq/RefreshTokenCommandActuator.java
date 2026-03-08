package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.platform.iam.IamQueryService;

/**
 * Actuator that handles the "refresh token" command for platform IAM.
 *
 * <p>Delegates to the {@link IamQueryService} which performs the OIDC refresh-token grant against
 * Keycloak. Routed through the command dispatcher so the resource layer remains free of
 * query-service calls on write paths.
 */
public class RefreshTokenCommandActuator
        implements Actuator<RefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

    private final IamQueryService<?> queryService;

    public RefreshTokenCommandActuator(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for refreshing a platform access token.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token
     */
    public record RefreshTokenCommand(String realmId, String refreshToken) {}

    @Override
    public AuthResult execute(EventService<?> publisher, RefreshTokenCommand command) {
        return queryService.refreshToken(command.realmId(), command.refreshToken());
    }
}
