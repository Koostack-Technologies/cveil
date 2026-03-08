package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;

/**
 * Actuator that handles the "authenticate" command for platform IAM.
 *
 * <p>Performs the OIDC Resource Owner Password Credentials grant directly against Keycloak
 * via {@link KeycloakAuthSupport}. No query-service dependency.
 */
public class AuthenticateCommandActuator
        implements Actuator<AuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private final KeycloakAuthSupport authSupport;

    public AuthenticateCommandActuator(KeycloakAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    /**
     * Command input for authenticating a platform user.
     *
     * @param realmId  the Keycloak realm
     * @param email    the user's email
     * @param password the user's password
     */
    public record AuthenticateCommand(String realmId, String email, String password) {}

    @Override
    public AuthResult execute(EventService<?> publisher, AuthenticateCommand command) {
        return authSupport.authenticate(command.realmId(), command.email(), command.password());
    }
}
