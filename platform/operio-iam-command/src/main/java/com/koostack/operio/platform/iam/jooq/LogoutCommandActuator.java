package com.koostack.operio.platform.iam.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.common.keycloak.KeycloakAuthSupport;
import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.UserLoggedOutEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "logout" command for platform IAM.
 *
 * <p>Revokes the refresh token via Keycloak's OIDC logout endpoint using {@link
 * KeycloakAuthSupport}, then publishes a {@link UserLoggedOutEvent}.
 */
public class LogoutCommandActuator
        implements CommandActuator<LogoutCommandActuator.LogoutCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(LogoutCommandActuator.class);
    private static final String USER_AGGREGATE_TYPE = "iam-user";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakAuthSupport authSupport;

    private final EventService<?> eventService;

    public LogoutCommandActuator(EventService<?> eventService, KeycloakAuthSupport authSupport) {
        this.eventService = eventService;
        this.authSupport = authSupport;
    }

    /**
     * Command input for logging out a platform user.
     *
     * @param realmId the Keycloak realm
     * @param refreshToken the refresh token to revoke
     */
    public record LogoutCommand(String realmId, String refreshToken) {}

    @Override
    public Void execute(LogoutCommand command) {
        // Extract userId from the refresh token JWT before revoking it
        String userId = extractSubject(command.refreshToken());

        // Keycloak side-effect
        authSupport.logout(command.realmId(), command.refreshToken());

        // Publish domain event
        if (userId != null) {
            var payload = new UserLoggedOutEvent(command.realmId(), userId);

            UUID aggregateId = UUID.fromString(userId);
            AggregateKey key =
                    new AggregateKey(
                            aggregateId, USER_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

            eventPublisher()
                    .publishEvent(
                            PublishDomainEventInput.of(
                                    PlatformConstants.PLATFORM_TENANT_ID,
                                    IamEventTypes.USER_LOGGED_OUT,
                                    key,
                                    payload));

            LOG.info("User logged out: realmId={}, userId={}", command.realmId(), userId);
        } else {
            LOG.warn(
                    "User logged out but could not extract userId from refresh token: realmId={}",
                    command.realmId());
        }

        return null;
    }

    /**
     * Extracts the {@code sub} claim from a JWT refresh token without verification. Returns {@code
     * null} if the token cannot be decoded.
     */
    private static String extractSubject(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = MAPPER.readTree(decoded);
            JsonNode sub = payload.get("sub");
            return sub != null ? sub.asText() : null;
        } catch (Exception e) {
            LOG.debug("Failed to extract subject from refresh token: {}", e.getMessage());
            return null;
        }
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
