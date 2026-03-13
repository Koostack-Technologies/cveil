package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.AccessTokenCreatedEvent;
import com.koostack.cveil.core.platform.iam.AccessTokenGenerator;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a personal access token (PAT) and stores it in the platform database.
 *
 * <p>Generates a {@code cveil_}-prefixed token via {@link AccessTokenGenerator}, persists the
 * SHA-256 hash to the {@code platform_iam.access_token} table, and publishes an {@link
 * AccessTokenCreatedEvent}.
 */
public class CreateAccessTokenCommandActuator
        implements CommandActuator<
                CreateAccessTokenCommandActuator.CreateAccessTokenCommand,
                CreateAccessTokenCommandActuator.CreateAccessTokenResult> {

    private static final Logger LOG =
            LoggerFactory.getLogger(CreateAccessTokenCommandActuator.class);
    private static final String ACCESS_TOKEN_AGGREGATE_TYPE = "iam-access-token";

    private final EventService<?> eventService;
    private final DataSource platformDataSource;

    public CreateAccessTokenCommandActuator(
            EventService<?> eventService, DataSource platformDataSource) {
        this.eventService = eventService;
        this.platformDataSource = platformDataSource;
    }

    /**
     * @param tenantId the tenant this token is scoped to
     * @param name human-readable name chosen by the user
     * @param roles comma-separated roles to snapshot at creation
     * @param createdBy the Keycloak user ID (subject) of the creator
     * @param expiresAt when the token expires (nullable for no expiry)
     */
    public record CreateAccessTokenCommand(
            String tenantId, String name, String roles, String createdBy, Instant expiresAt) {}

    /**
     * @param plaintextToken the full token to show to the user (once only)
     * @param tokenPrefix the display prefix for logs and UI
     */
    public record CreateAccessTokenResult(String plaintextToken, String tokenPrefix) {}

    @Override
    public CreateAccessTokenResult execute(CreateAccessTokenCommand command) {
        AccessTokenGenerator.GeneratedToken generated = AccessTokenGenerator.generate();

        // Persist the token hash to the database
        String sql =
                "INSERT INTO platform_iam.access_token"
                        + " (token_hash, token_prefix, name, tenant_id, roles, status,"
                        + " expires_at, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'active', ?, ?, CURRENT_TIMESTAMP)";
        try (var conn = platformDataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setString(1, generated.tokenHash());
            ps.setString(2, generated.tokenPrefix());
            ps.setString(3, command.name());
            ps.setString(4, command.tenantId());
            ps.setString(5, command.roles());
            ps.setTimestamp(
                    6, command.expiresAt() != null ? Timestamp.from(command.expiresAt()) : null);
            ps.setString(7, command.createdBy());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error(
                    "Failed to persist access token: tenantId={}, name={}",
                    command.tenantId(),
                    command.name(),
                    e);
            throw new IllegalStateException("Failed to create access token: " + e.getMessage(), e);
        }

        // Publish domain event
        String aggregateIdSource = command.tenantId() + ":" + generated.tokenPrefix();
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
                                IamEventTypes.ACCESS_TOKEN_CREATED,
                                key,
                                new AccessTokenCreatedEvent(
                                        command.tenantId(),
                                        generated.tokenPrefix(),
                                        command.name(),
                                        command.roles(),
                                        command.createdBy(),
                                        command.expiresAt())));

        LOG.info(
                "Access token created: prefix={}, tenantId={}, createdBy={}",
                generated.tokenPrefix(),
                command.tenantId(),
                command.createdBy());
        return new CreateAccessTokenResult(generated.plaintextToken(), generated.tokenPrefix());
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
