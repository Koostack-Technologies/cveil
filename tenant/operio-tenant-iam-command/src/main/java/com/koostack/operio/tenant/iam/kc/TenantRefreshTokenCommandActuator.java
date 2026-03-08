package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;

/**
 * Actuator that handles the "refresh token" command for tenant IAM.
 *
 * <p>Performs the OIDC refresh-token grant directly against the tenant's Keycloak realm
 * via {@link KeycloakAuthSupport}. No query-service dependency.
 */
public class TenantRefreshTokenCommandActuator
        implements Actuator<TenantRefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

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
        return authSupport.refreshToken(command.tenantId(), command.refreshToken());
    }
}
