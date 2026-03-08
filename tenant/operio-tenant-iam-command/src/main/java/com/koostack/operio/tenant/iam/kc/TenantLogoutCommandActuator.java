package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;

/**
 * Actuator that handles the "logout" command for tenant IAM.
 *
 * <p>Revokes the refresh token via Keycloak's OIDC logout endpoint using
 * {@link KeycloakAuthSupport}. No query-service dependency.
 */
public class TenantLogoutCommandActuator
        implements Actuator<TenantLogoutCommandActuator.LogoutCommand, Void> {

    private final KeycloakAuthSupport authSupport;

    public TenantLogoutCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for logging out a tenant user.
     *
     * @param tenantId     the tenant identifier (maps to Keycloak realm)
     * @param refreshToken the refresh token to revoke
     */
    public record LogoutCommand(String tenantId, String refreshToken) {}

    @Override
    public Void execute(EventService<?> publisher, LogoutCommand command) {
        authSupport.logout(command.tenantId(), command.refreshToken());
        return null;
    }
}
