package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;

/**
 * Actuator that handles the "authenticate" command for tenant IAM.
 *
 * <p>Performs the OIDC Resource Owner Password Credentials grant directly against the
 * tenant's Keycloak realm via {@link TenantKeycloakAuthSupport}. No query-service dependency.
 */
public class TenantAuthenticateCommandActuator
        implements Actuator<TenantAuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private final TenantKeycloakAuthSupport authSupport;

    public TenantAuthenticateCommandActuator(TenantKeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for authenticating a tenant user.
     *
     * @param tenantId the tenant identifier (maps to Keycloak realm)
     * @param email    the user's email
     * @param password the user's password
     */
    public record AuthenticateCommand(String tenantId, String email, String password) {}

    @Override
    public AuthResult execute(EventService<?> publisher, AuthenticateCommand command) {
        return authSupport.authenticate(command.tenantId(), command.email(), command.password());
    }
}
