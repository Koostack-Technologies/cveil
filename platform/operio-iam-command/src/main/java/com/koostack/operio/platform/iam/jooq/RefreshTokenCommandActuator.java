package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;

/**
 * Actuator that handles the "refresh token" command for platform IAM.
 *
 * <p>Performs the OIDC refresh-token grant directly against Keycloak
 * via {@link KeycloakAuthSupport}. No query-service dependency.
 */
public class RefreshTokenCommandActuator
        implements Actuator<RefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

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
        return authSupport.refreshToken(command.realmId(), command.refreshToken());
    }
}
