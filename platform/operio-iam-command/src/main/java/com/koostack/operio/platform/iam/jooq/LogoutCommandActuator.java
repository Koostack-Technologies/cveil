package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;

/**
 * Actuator that handles the "logout" command for platform IAM.
 *
 * <p>Revokes the refresh token via Keycloak's OIDC logout endpoint using
 * {@link KeycloakAuthSupport}. No query-service dependency.
 */
public class LogoutCommandActuator
        implements Actuator<LogoutCommandActuator.LogoutCommand, Void> {

    private final KeycloakAuthSupport authSupport;

    public LogoutCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for logging out a platform user.
     *
     * @param realmId      the Keycloak realm
     * @param refreshToken the refresh token to revoke
     */
    public record LogoutCommand(String realmId, String refreshToken) {}

    @Override
    public Void execute(EventService<?> publisher, LogoutCommand command) {
        authSupport.logout(command.realmId(), command.refreshToken());
        return null;
    }
}
