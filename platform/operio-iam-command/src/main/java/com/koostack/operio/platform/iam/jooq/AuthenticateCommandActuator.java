package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.platform.iam.IamQueryService;

/**
 * Actuator that handles the "authenticate" command for platform IAM.
 *
 * <p>Delegates to the {@link IamQueryService} which performs the OIDC Resource Owner Password
 * Credentials grant against Keycloak. Although this is a side-effecting operation (creates a
 * session / issues tokens), it is routed through the command dispatcher so the resource layer
 * remains free of query-service calls on write paths.
 */
public class AuthenticateCommandActuator
        implements Actuator<AuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private final IamQueryService<?> queryService;

    public AuthenticateCommandActuator(IamQueryService<?> queryService) {
        this.queryService = queryService;
    }

    /**
     * Command input for authenticating a platform user.
     *
     * @param realmId the Keycloak realm
     * @param email the user's email
     * @param password the user's password
     */
    public record AuthenticateCommand(String realmId, String email, String password) {}

    @Override
    public AuthResult execute(EventService<?> publisher, AuthenticateCommand command) {
        return queryService.authenticate(command.realmId(), command.email(), command.password());
    }
}
