package com.koostack.operio.tenant.iam.kc;

import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.core.tenant.events.TenantUserAuthenticatedEvent;
import com.koostack.operio.core.tenant.iam.TenantIamEventTypes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "authenticate" command for tenant IAM.
 *
 * <p>Performs the OIDC Resource Owner Password Credentials grant directly against the
 * tenant's Keycloak realm via {@link KeycloakAuthSupport}, then publishes a
 * {@link TenantUserAuthenticatedEvent}.
 */
public class TenantAuthenticateCommandActuator
        implements CommandActuator<TenantAuthenticateCommandActuator.AuthenticateCommand, AuthResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantAuthenticateCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "tenant-iam-user";

    private final KeycloakAuthSupport authSupport;

    private final EventService<?> eventService;

    public TenantAuthenticateCommandActuator(EventService<?> eventService, KeycloakAuthSupport authSupport) {
        this.eventService = eventService;
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
    public AuthResult execute(AuthenticateCommand command) {
        AuthResult result =
                authSupport.authenticate(command.tenantId(), command.email(), command.password());

        // Publish domain event
        String userId = result.identity().subject();
        var payload =
                new TenantUserAuthenticatedEvent(command.tenantId(), userId, command.email());

        UUID aggregateId = UUID.fromString(userId);
        AggregateKey key =
                new AggregateKey(aggregateId, USER_AGGREGATE_TYPE, command.tenantId());

        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        command.tenantId(),
                        TenantIamEventTypes.USER_AUTHENTICATED,
                        key,
                        payload));

        LOG.info(
                "Tenant user authenticated: tenantId={}, email={}, userId={}",
                command.tenantId(),
                command.email(),
                userId);

        return result;
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
