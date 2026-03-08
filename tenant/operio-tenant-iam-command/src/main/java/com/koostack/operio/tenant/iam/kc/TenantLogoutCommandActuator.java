package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;

/**
 * Actuator that handles the "logout" command for tenant IAM.
 *
 * <p>Delegates to the {@link TenantIamQueryService} which revokes the refresh token via Keycloak's
 * OIDC logout endpoint for the tenant's realm.
 */
public class TenantLogoutCommandActuator
        implements Actuator<TenantLogoutCommandActuator.LogoutCommand, Void> {

    private final TenantIamQueryService<?> queryService;

    public TenantLogoutCommandActuator(TenantIamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for logging out a tenant user.
     *
     * @param tenantId the tenant identifier (maps to Keycloak realm)
     * @param refreshToken the refresh token to revoke
     */
    public record LogoutCommand(String tenantId, String refreshToken) {}

    @Override
    public Void execute(EventService<?> publisher, LogoutCommand command) {
        queryService.logout(command.tenantId(), command.refreshToken());
        return null;
    }
}
