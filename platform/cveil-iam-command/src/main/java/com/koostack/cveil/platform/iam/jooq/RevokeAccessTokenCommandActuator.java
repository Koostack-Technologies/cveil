package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.AccessTokenRevokedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Revokes a personal access token by setting its status to {@code "revoked"} in the platform
 * database.
 *
 * <p>Updates the {@code cveiliam.access_token} table and publishes an {@link
 * AccessTokenRevokedEvent}.
 */
public class RevokeAccessTokenCommandActuator
        implements CommandActuator<
                RevokeAccessTokenCommandActuator.RevokeAccessTokenCommand,
                RevokeAccessTokenCommandActuator.RevokeAccessTokenResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RevokeAccessTokenCommandActuator.class);
    private static final String ACCESS_TOKEN_AGGREGATE_TYPE = "iam-access-token";

    private final EventService<?> eventService;
    private final DataSource platformDataSource;

    public RevokeAccessTokenCommandActuator(
            EventService<?> eventService, DataSource platformDataSource) {
        this.eventService = eventService;
        this.platformDataSource = platformDataSource;
    }

    /**
     * @param tenantId the tenant the token is scoped to
     * @param tokenPrefix the display prefix identifying the token to revoke
     */
    public record RevokeAccessTokenCommand(String tenantId, String tokenPrefix) {}

    public record RevokeAccessTokenResult(String tokenPrefix, boolean success) {}

    @Override
    public RevokeAccessTokenResult execute(RevokeAccessTokenCommand command) {
        if (command.tokenPrefix() == null || command.tokenPrefix().isBlank()) {
            throw new IllegalArgumentException("tokenPrefix is required");
        }

        // Update the token status in the database
        String sql =
                "UPDATE cveiliam.access_token"
                        + " SET status = 'revoked', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE tenant_id = ? AND token_prefix = ? AND status = 'active'";
        int rowsUpdated;
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, command.tenantId());
            ps.setString(2, command.tokenPrefix());
            rowsUpdated = ps.executeUpdate();
        } catch (Exception e) {
            LOG.error(
                    "Failed to revoke access token: tenantId={}, tokenPrefix={}",
                    command.tenantId(),
                    command.tokenPrefix(),
                    e);
            throw new IllegalStateException("Failed to revoke access token: " + e.getMessage(), e);
        }

        if (rowsUpdated == 0) {
            LOG.warn(
                    "No active token found to revoke: tenantId={}, tokenPrefix={}",
                    command.tenantId(),
                    command.tokenPrefix());
            return new RevokeAccessTokenResult(command.tokenPrefix(), false);
        }

        // Publish domain event
        String aggregateIdSource = command.tenantId() + ":" + command.tokenPrefix();
        UUID aggregateId = UUID.nameUUIDFromBytes(aggregateIdSource.getBytes());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        ACCESS_TOKEN_AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.ACCESS_TOKEN_REVOKED,
                                key,
                                new AccessTokenRevokedEvent(
                                        command.tenantId(), command.tokenPrefix())));

        LOG.info(
                "Access token revoked: tokenPrefix={}, tenantId={}",
                command.tokenPrefix(),
                command.tenantId());
        return new RevokeAccessTokenResult(command.tokenPrefix(), true);
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
