package com.koostack.cveil.platform.iam.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.events.RedisProvisionedEvent;
import com.koostack.cveil.core.platform.iam.IamEventTypes;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Provisions Redis infrastructure for a tenant/organization: stream, pub/sub channel, and
 * optionally an ACL user.
 *
 * <p>Replaces the Redis provisioning that was previously embedded in {@code
 * TenantManagerProjectionHandler}.
 */
public class ProvisionRedisCommandActuator
        implements CommandActuator<
                ProvisionRedisCommandActuator.ProvisionRedisCommand,
                ProvisionRedisCommandActuator.ProvisionRedisResult> {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionRedisCommandActuator.class);
    private static final String REDIS_AGGREGATE_TYPE = "iam-redis";

    private final EventService<?> eventService;
    private final JedisPooled jedis;

    public ProvisionRedisCommandActuator(EventService<?> eventService, JedisPooled jedis) {
        this.eventService = eventService;
        this.jedis = jedis;
    }

    /**
     * @param tenantId the tenant identifier
     * @param aclUser optional Redis ACL user to create (null to skip ACL provisioning)
     * @param aclPassword optional password for the ACL user (null to skip)
     */
    public record ProvisionRedisCommand(String tenantId, String aclUser, String aclPassword) {
        public ProvisionRedisCommand(String tenantId) {
            this(tenantId, null, null);
        }
    }

    public record ProvisionRedisResult(String streamKey, String pubSubChannel, String aclUser) {}

    @Override
    public ProvisionRedisResult execute(ProvisionRedisCommand command) {
        String tenantId = command.tenantId();
        // Shard streams are created at startup by ShardedStreamConsumer —
        // no per-tenant stream creation needed.
        // Must match RedisEventPublisher.DEFAULT_SHARD_COUNT
        int shard = Math.floorMod(tenantId.hashCode(), 16);
        String streamKey = "cveil:stream:shard:" + shard;
        String pubSubChannel = "consent-cache-invalidation:" + tenantId;

        // Auto-generate ACL credentials when not explicitly provided
        String aclUser = command.aclUser() != null ? command.aclUser() : "t_" + tenantId;
        String aclPassword =
                command.aclPassword() != null
                        ? command.aclPassword()
                        : UUID.randomUUID().toString();

        // Create ACL user for tenant data keys (graph nodes, consent cache).
        // Streams use the admin connection — no per-tenant ACL needed for streams.
        // Grant all command categories except admin (@admin: ACL, CONFIG, CLIENT, etc.)
        // and dangerous (@dangerous: FLUSHDB, FLUSHALL, DEBUG, KEYS, etc.).
        try {
            jedis.sendCommand(
                    () -> "ACL".getBytes(StandardCharsets.UTF_8),
                    "SETUSER".getBytes(StandardCharsets.UTF_8),
                    aclUser.getBytes(StandardCharsets.UTF_8),
                    "on".getBytes(StandardCharsets.UTF_8),
                    "resetkeys".getBytes(StandardCharsets.UTF_8),
                    "resetchannels".getBytes(StandardCharsets.UTF_8),
                    (">" + aclPassword).getBytes(StandardCharsets.UTF_8),
                    ("~" + tenantId + ":*").getBytes(StandardCharsets.UTF_8),
                    ("~" + pubSubChannel).getBytes(StandardCharsets.UTF_8),
                    ("&" + pubSubChannel).getBytes(StandardCharsets.UTF_8),
                    "+@all".getBytes(StandardCharsets.UTF_8),
                    "-@admin".getBytes(StandardCharsets.UTF_8),
                    "-@dangerous".getBytes(StandardCharsets.UTF_8));
            LOG.info("Redis ACL user created: {}", aclUser);
        } catch (Exception e) {
            LOG.warn("Redis ACL setup failed (non-fatal): {}", e.getMessage());
        }

        // Publish domain event (includes credentials for projection to store)
        var payload =
                new RedisProvisionedEvent(tenantId, streamKey, pubSubChannel, aclUser, aclPassword);

        UUID aggregateId =
                UUID.nameUUIDFromBytes(("redis:" + tenantId).getBytes(StandardCharsets.UTF_8));
        AggregateKey key =
                new AggregateKey(
                        aggregateId, REDIS_AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                IamEventTypes.REDIS_PROVISIONED,
                                key,
                                payload));

        LOG.info(
                "Redis provisioned for tenant {}: stream={}, pubsub={}, aclUser={}",
                tenantId,
                streamKey,
                pubSubChannel,
                aclUser);
        return new ProvisionRedisResult(streamKey, pubSubChannel, aclUser);
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
