package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.iam.IamQueryService;

/**
 * Actuator that handles the "logout" command for platform IAM.
 *
 * <p>Delegates to the {@link IamQueryService} which revokes the refresh token via Keycloak's OIDC
 * logout endpoint. Routed through the command dispatcher so the resource layer remains free of
 * query-service calls on write paths.
 */
public class LogoutCommandActuator implements Actuator<LogoutCommandActuator.LogoutCommand, Void> {

    private final IamQueryService<?> queryService;

    public LogoutCommandActuator(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for logging out a platform user.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token to revoke
     */
    public record LogoutCommand(String realmId, String refreshToken) {}

    @Override
    public Void execute(EventService<?> publisher, LogoutCommand command) {
        queryService.logout(command.realmId(), command.refreshToken());
        return null;
    }
}
