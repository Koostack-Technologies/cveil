package com.koostack.operio.plugin.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.PlatformEventService;
import com.koostack.operio.core.event.models.AggregateSnapshotTrigger;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import com.koostack.operio.plugin.jooq.common.JooqService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisContextFactory;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * jOOQ-backed {@link PlatformEventService} implementation — durable event
 * store for platform-level domain events.
 *
 * <p>Events are appended to an append-only {@code platform_event} table with
 * optimistic concurrency control via per-aggregate versioning. A unique
 * constraint on {@code (aggregate_type, aggregate_id, version)} prevents
 * duplicate writes.
 */
public class JooqPlatformEventService
    extends JooqService
    implements PlatformEventService<PlatformEventJooqConfig>
{

    private static final Logger log = LoggerFactory.getLogger(JooqPlatformEventService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final int SNAPSHOT_DEPTH_THRESHOLD = 20;

    private DSLContext dslContext;
    private EventRepository eventRepository;
    private EventTypeCache eventTypeCache;
    private RedisEventPublisher redisPublisher;
    private OutboxPoller outboxPoller;

    private final LinkedBlockingQueue<AggregateSnapshotTrigger> snapshotQueue = new LinkedBlockingQueue<>();
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
        String migrationLocation = resolveMigrationLocation(config.dialect());
        this.dslContext = JooqContextFactory.createTenantAwareContext(
            config,
            "operio-platform-event-jooq",
            migrationLocation
        );

        this.eventRepository = new EventRepository(dslContext);
        this.eventTypeCache = new EventTypeCache(eventRepository);

        if (config.redis() != null) {
            if (config.tenantCredentialLookup() == null) {
                throw new IllegalStateException(
                    "Redis is enabled but no TenantCredentialLookup was provided. " +
                    "A tenant credential lookup is required for per-tenant Redis ACL isolation."
                );
            }
            if (config.tenantDiscovery() == null) {
                throw new IllegalStateException(
                    "Redis is enabled but no tenantDiscovery supplier was provided. " +
                    "A tenant discovery supplier is required so the outbox poller can " +
                    "iterate per-tenant schemas."
                );
            }
            JedisProvider jedisProvider = RedisContextFactory.createProvider(
                config.redis(), config.tenantCredentialLookup()
            );
            this.redisPublisher = new RedisEventPublisher(jedisProvider, "operio");
            this.outboxPoller = new OutboxPoller(dslContext, redisPublisher, config.tenantDiscovery());
            this.outboxPoller.start(2);
        }

        log.info(
            "JooqPlatformEventService configured (dialect={}, redis={}, tenantAcl={})",
            config.dialect(),
            config.redis() != null ? "enabled" : "disabled",
            config.tenantCredentialLookup() != null ? "enabled" : "disabled"
        );

        this.snapshotWorker = new SnapshotWorker(snapshotQueue, eventRepository);
        this.snapshotWorker.start(2);
    }

    @Override
    public boolean isReady() {
        return dslContext != null;
    }

    /**
     * Shuts down background workers (outbox poller and snapshot worker).
     * Safe to call multiple times or before {@link #configure}.
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
        TenantContext.runWhere(input.tenantId(), () -> {
            try {
                if (!eventTypeCache.isValid(input.eventType())) {
                    throw new OperioServiceException(
                        "Unknown event type: " + input.eventType()
                    );
                }

                String aggregateType = input.key().aggregateType();
                String aggregateId = input.key().aggregateId().toString();
                String serializedPayload = objectMapper.writeValueAsString(input.payload());

                dslContext.transaction(txConfig -> {
                    DSLContext txDsl = txConfig.dsl();
                    EventRepository txRepo = new EventRepository(txDsl);

                    Long version = txRepo.getAggregateVersion(aggregateType, aggregateId) + 1;
                    PlatformEvent event = new PlatformEvent(
                        null,
                        input.key().aggregateId(),
                        aggregateType,
                        input.eventType(),
                        java.time.Instant.now(),
                        version,
                        serializedPayload
                    );

                    long eventId = txRepo.insert(event);
                    log.debug(
                        "Published event {} for {}/{}",
                        eventId, aggregateType, aggregateId
                    );
                });
            } catch (OperioServiceException e) {
                throw e;
            } catch (DataAccessException e) {
                throw new OperioServiceException(
                    "Concurrent modification conflict for "
                        + input.key().aggregateType() + "/" + input.key().aggregateId()
                        + " — retry the operation",
                    e
                );
            } catch (Exception e) {
                throw new OperioServiceException(
                    "Failed to publish event for " + input.key().aggregateType()
                        + "/" + input.key().aggregateId(),
                    e
                );
            }
        });
    }

    @Override
    public Long getAggregateVersion(AggregateKey key) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        try {
            return TenantContext.callWhere(key.tenantId(), () ->
                eventRepository.getAggregateVersion(key.aggregateType(), key.aggregateId().toString())
            );
        } catch (OperioServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to get aggregate version for " + key.aggregateType() + "/" + key.aggregateId(), e);
        }
    }

    @Override
    public <U> U getAggregate(AggregateKey key, Class<U> type) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        try {
            return TenantContext.callWhere(key.tenantId(), () -> {
                String aggregateType = key.aggregateType();
                String aggregateId = key.aggregateId().toString();
                List<PlatformEvent> events;

                var snapshot = eventRepository.fetchLatestSnapshot(aggregateType, aggregateId);
                if (snapshot.isPresent()) {
                    JsonNode baseState = objectMapper.readTree(snapshot.get().snapshot());
                    events = eventRepository.fetchChangesAfterVersion(
                        aggregateType, aggregateId, snapshot.get().version()
                    );
                    JsonNode result = replayPayloads(baseState, events);
                    triggerSnapshotIfNeeded(aggregateType, aggregateId, events);
                    return objectMapper.treeToValue(result, type);
                } else {
                    events = eventRepository.fetchChangesForAggregate(aggregateType, aggregateId);
                    JsonNode result = replayPayloads(objectMapper.createObjectNode(), events);
                    triggerSnapshotIfNeeded(aggregateType, aggregateId, events);
                    return objectMapper.treeToValue(result, type);
                }
            });
        } catch (OperioServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to hydrate aggregate " + key.aggregateType() + "/" + key.aggregateId(), e);
        }
    }

    @Override
    public <U> void registerEventType(RegisterEventTypeInput<U> input) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        eventRepository.insertEventType(input);
        eventTypeCache.refresh();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Replays semantic event payloads by deep-merging each payload into the
     * running state.  Platform aggregates do not yet have typed {@code apply()}
     * methods (the command services are empty interfaces), so this performs a
     * simple JSON overlay: each payload's fields overwrite matching keys in
     * the current state.
     */
    private JsonNode replayPayloads(JsonNode baseState, List<PlatformEvent> events) {
        if (events.isEmpty()) {
            return baseState;
        }
        com.fasterxml.jackson.databind.node.ObjectNode state =
            baseState.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) baseState.deepCopy()
                : objectMapper.createObjectNode();
        for (PlatformEvent event : events) {
            if (event.payload() != null) {
                try {
                    JsonNode payloadNode = objectMapper.readTree(event.payload());
                    if (payloadNode.isObject()) {
                        payloadNode.fields().forEachRemaining(
                            field -> state.set(field.getKey(), field.getValue())
                        );
                    }
                } catch (Exception e) {
                    throw new OperioServiceException(
                        "Failed to replay payload for event " + event.eventId()
                            + " (" + event.eventType() + ")", e);
                }
            }
        }
        return state;
    }

    private void triggerSnapshotIfNeeded(
        String aggregateType, String aggregateId, List<PlatformEvent> events
    ) {
        int depth = events.size();
        if (depth >= SNAPSHOT_DEPTH_THRESHOLD) {
            AggregateKey key = new AggregateKey(
                UUID.fromString(aggregateId), aggregateType, TenantContext.get()
            );
            long targetVersion = events.getLast().version();
            snapshotQueue.offer(new AggregateSnapshotTrigger(key, targetVersion));
            log.debug(
                "Snapshot trigger enqueued for {}/{} (depth={})",
                aggregateType, aggregateId, depth
            );
        }
    }

    // -----------------------------------------------------------------------
    // JooqService
    // -----------------------------------------------------------------------

    @Override
    protected String getSchemaName() {
        return "platform-event";
    }
}
