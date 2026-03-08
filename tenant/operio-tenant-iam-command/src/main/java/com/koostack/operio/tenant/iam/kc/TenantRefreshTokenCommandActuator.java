package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.tenant.iam.TenantIamQueryService;

/**
 * Actuator that handles the "refresh token" command for tenant IAM.
 *
 * <p>Delegates to the {@link TenantIamQueryService} which performs the OIDC refresh-token grant
 * against the tenant's Keycloak realm.
 */
public class TenantRefreshTokenCommandActuator
        implements Actuator<TenantRefreshTokenCommandActuator.RefreshTokenCommand, AuthResult> {

    private final TenantIamQueryService<?> queryService;

    public TenantRefreshTokenCommandActuator(TenantIamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for refreshing a tenant access token.
     *
     * @param tenantId the tenant identifier (maps to Keycloak realm)
     * @param refreshToken the refresh token
     */
    public record RefreshTokenCommand(String tenantId, String refreshToken) {}

    @Override
    public AuthResult execute(EventService<?> publisher, RefreshTokenCommand command) {
        return queryService.refreshToken(command.tenantId(), command.refreshToken());
    }
}
