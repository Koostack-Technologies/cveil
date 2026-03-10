package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.events.RedisProvisionedEvent;
import com.koostack.operio.core.platform.iam.IamEventTypes;
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
        String streamKey = "operio:stream:tenant:" + tenantId;
        String pubSubChannel = "consent-cache-invalidation:" + tenantId;

        // Create the tenant event stream with an initial system message
        try {
            var params = new java.util.HashMap<String, String>();
            params.put("type", "stream-created");
            params.put("tenant", tenantId);
            jedis.xadd(streamKey, params, new redis.clients.jedis.params.XAddParams());
            LOG.info("Redis stream created: {}", streamKey);
        } catch (Exception e) {
            LOG.warn("Redis stream creation (may already exist): {}", e.getMessage());
        }

        // Create ACL user if requested
        if (command.aclUser() != null && command.aclPassword() != null) {
            try {
                String aclRule =
                        String.format(
                                "ACL SETUSER %s on >%s ~%s:* ~%s &%s +@all",
                                command.aclUser(),
                                command.aclPassword(),
                                "operio:stream:tenant:" + tenantId,
                                pubSubChannel,
                                pubSubChannel);
                jedis.sendCommand(
                        () -> "ACL".getBytes(StandardCharsets.UTF_8),
                        "SETUSER".getBytes(StandardCharsets.UTF_8),
                        command.aclUser().getBytes(StandardCharsets.UTF_8),
                        "on".getBytes(StandardCharsets.UTF_8),
                        (">" + command.aclPassword()).getBytes(StandardCharsets.UTF_8),
                        ("~operio:stream:tenant:" + tenantId + ":*")
                                .getBytes(StandardCharsets.UTF_8),
                        ("~" + pubSubChannel).getBytes(StandardCharsets.UTF_8),
                        ("&" + pubSubChannel).getBytes(StandardCharsets.UTF_8),
                        "+@all".getBytes(StandardCharsets.UTF_8));
                LOG.info("Redis ACL user created: {}", command.aclUser());
            } catch (Exception e) {
                LOG.warn("Redis ACL setup failed (non-fatal): {}", e.getMessage());
            }
        }

        // Publish domain event
        var payload =
                new RedisProvisionedEvent(tenantId, streamKey, pubSubChannel, command.aclUser());

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
                "Redis provisioned for tenant {}: stream={}, pubsub={}",
                tenantId,
                streamKey,
                pubSubChannel);
        return new ProvisionRedisResult(streamKey, pubSubChannel, command.aclUser());
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
