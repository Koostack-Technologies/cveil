package com.koostack.operio.plugin.event.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.PlatformEventService;
import com.koostack.operio.core.event.models.AggregateSnapshotTrigger;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.RedisContextFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-backed {@link PlatformEventService} implementation — durable event store for platform-level
 * domain events.
 *
 * <p>Events are appended to an append-only {@code platform_event} table with optimistic concurrency
 * control via per-aggregate versioning. A unique constraint on {@code (aggregate_type,
 * aggregate_id, version)} prevents duplicate writes.
 */
public class JooqPlatformEventService implements PlatformEventService<PlatformEventJooqConfig> {

    private static final Logger log = LoggerFactory.getLogger(JooqPlatformEventService.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int SNAPSHOT_DEPTH_THRESHOLD = 20;

    private DSLContext dslContext;
    private EventRepository eventRepository;
    private RedisEventPublisher redisPublisher;
    private OutboxPoller outboxPoller;

    private final LinkedBlockingQueue<AggregateSnapshotTrigger> snapshotQueue =
            new LinkedBlockingQueue<>();
    private SnapshotWorker snapshotWorker;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-platform-event";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<PlatformEventJooqConfig> getConfigurationType() {
        return PlatformEventJooqConfig.class;
    }

    @Override
    public void configure(PlatformEventJooqConfig config) {
        this.dslContext =
                JooqContextFactory.createTenantAwareContext(config, "operio-platform-event-jooq");

        this.eventRepository = new EventRepository(dslContext);

        if (config.redis() != null) {
            JedisProvider jedisProvider;
            if (config.tenantCredentialLookup() != null) {
                jedisProvider =
                        RedisContextFactory.createProvider(
                                config.redis(), config.tenantCredentialLookup());
            } else {
                // Platform-only mode: no per-tenant Redis ACL needed
                jedisProvider =
                        JedisProvider.shared(
                                RedisClientFactory.createClient(
                                        config.redis().host(),
                                        config.redis().port(),
                                        config.redis().password(),
                                        config.redis().tlsConfig()));
            }
            this.redisPublisher = new RedisEventPublisher(jedisProvider, "operio");

            Supplier<List<String>> discovery =
                    config.tenantDiscovery() != null
                            ? config.tenantDiscovery()
                            : () -> List.of("platform");
            this.outboxPoller = new OutboxPoller(dslContext, redisPublisher, discovery);
            this.outboxPoller.start(2);
        }

        log.info(
                "JooqPlatformEventService configured (dialect={}, redis={}, tenantAcl={})",
                config.dialect(),
                config.redis() != null ? "enabled" : "disabled",
                config.tenantCredentialLookup() != null ? "enabled" : "disabled");

        this.snapshotWorker = new SnapshotWorker(snapshotQueue, eventRepository);
        this.snapshotWorker.start(2);
    }

    @Override
    public boolean isReady() {
        return dslContext != null;
    }

    /**
     * Shuts down background workers (outbox poller and snapshot worker). Safe to call multiple
     * times or before {@link #configure}.
     */
    public void stop() {
        if (outboxPoller != null) {
            outboxPoller.stop();
        }
        if (snapshotWorker != null) {
            snapshotWorker.stop();
        }
    }

    // -----------------------------------------------------------------------
    // PlatformEventService
    // -----------------------------------------------------------------------

    @Override
    public void publishEvent(PublishDomainEventInput input) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        TenantContext.runWhere(
                input.tenantId(),
                () -> {
                    try {
                        String aggregateType = input.key().aggregateType();
                        String aggregateId = input.key().aggregateId().toString();
                        String serializedPayload = objectMapper.writeValueAsString(input.payload());

                        dslContext.transaction(
                                txConfig -> {
                                    DSLContext txDsl = txConfig.dsl();
                                    EventRepository txRepo = new EventRepository(txDsl);

                                    Long version =
                                            txRepo.getAggregateVersion(aggregateType, aggregateId)
                                                    + 1;
                                    PlatformEvent event =
                                            new PlatformEvent(
                                                    null,
                                                    input.key().aggregateId(),
                                                    aggregateType,
                                                    input.eventType(),
                                                    java.time.Instant.now(),
                                                    version,
                                                    serializedPayload);

                                    long eventId = txRepo.insert(event);
                                    log.debug(
                                            "Published event {} for {}/{}",
                                            eventId,
                                            aggregateType,
                                            aggregateId);

                                    if (version % SNAPSHOT_DEPTH_THRESHOLD == 0) {
                                        triggerSnapshot(aggregateType, aggregateId, version);
                                    }
                                });
                    } catch (OperioServiceException e) {
                        throw e;
                    } catch (DataAccessException e) {
                        throw new OperioServiceException(
                                "Concurrent modification conflict for "
                                        + input.key().aggregateType()
                                        + "/"
                                        + input.key().aggregateId()
                                        + " — retry the operation",
                                e);
                    } catch (Exception e) {
                        throw new OperioServiceException(
                                "Failed to publish event for "
                                        + input.key().aggregateType()
                                        + "/"
                                        + input.key().aggregateId(),
                                e);
                    }
                });
    }

    @Override
    public Long getAggregateVersion(AggregateKey key) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        try {
            return TenantContext.callWhere(
                    key.tenantId(),
                    () ->
                            eventRepository.getAggregateVersion(
                                    key.aggregateType(), key.aggregateId().toString()));
        } catch (OperioServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioServiceException(
                    "Failed to get aggregate version for "
                            + key.aggregateType()
                            + "/"
                            + key.aggregateId(),
                    e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void triggerSnapshot(String aggregateType, String aggregateId, long targetVersion) {
        AggregateKey key =
                new AggregateKey(UUID.fromString(aggregateId), aggregateType, TenantContext.get());
        snapshotQueue.offer(new AggregateSnapshotTrigger(key, targetVersion));
        log.debug(
                "Snapshot trigger enqueued for {}/{} at version {}",
                aggregateType,
                aggregateId,
                targetVersion);
    }
}
