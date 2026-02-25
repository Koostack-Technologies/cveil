package com.koostack.operio.plugin.event.jooq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.diff.JsonDiff;
import com.koostack.operio.core.event.PlatformEventService;
import com.koostack.operio.core.event.models.AggregateSnapshotTrigger;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.event.models.PublishEventInput;
import com.koostack.operio.core.event.models.RegisterEventTypeInput;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.AggregateRoot;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();
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

    // -----------------------------------------------------------------------
    // PlatformEventService
    // -----------------------------------------------------------------------

    @Override
    public <U extends AggregateRoot> void publish(PublishEventInput<U> input) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        // todo look into this pattern of wrapping the entire method in TenantContext.runWhere — is it better to do this at a higher level (e.g. in a service layer) so that the context is available for the entire operation, including any repository calls? Or is it better to do it here to ensure that all operations are properly scoped, even if the caller forgets to set the context?
        TenantContext.runWhere(input.tenantId(), () -> {
            try {
                if (!eventTypeCache.isValid(input.eventType())) {
                    throw new OperioServiceException(
                        "Unknown event type: " + input.eventType()
                    );
                }

                U state = input.state();
                String aggregateType = state.aggregateType();
                String aggregateId = state.aggregateId().toString();

                JsonNode currentState = hydrateAggregate(aggregateType, aggregateId);
                JsonNode updatedNode = objectMapper.valueToTree(state);
                JsonPatch diff = JsonDiff.asJsonPatch(currentState, updatedNode);

                Long version = eventRepository.getAggregateVersion(aggregateType, aggregateId) + 1;
                PlatformEvent event = new PlatformEvent(
                    null,
                    input.tenantId(),
                    state.aggregateId(),
                    aggregateType,
                    input.eventType(),
                    java.time.Instant.now(),
                    version,
                    diff
                );

                String serializedChanges = objectMapper.writeValueAsString(diff);
                long eventId = eventRepository.insert(event, serializedChanges);
                log.debug(
                    "Published event {} for {}/{}",
                    eventId, aggregateType, aggregateId
                );
            } catch (OperioServiceException e) {
                throw e;
            } catch (DataAccessException e) {
                throw new OperioServiceException(
                    "Concurrent modification conflict for "
                        + input.state().aggregateType() + "/" + input.state().aggregateId()
                        + " — retry the operation",
                    e
                );
            } catch (Exception e) {
                throw new OperioServiceException(
                    "Failed to publish event for " + input.state().aggregateType()
                        + "/" + input.state().aggregateId(),
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
                JsonNode state = hydrateAggregate(key.aggregateType(), key.aggregateId().toString());
                return objectMapper.treeToValue(state, type);
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

    private JsonNode hydrateAggregate(String aggregateType, String aggregateId)
            throws Exception {
        JsonNode state;
        List<PlatformEvent> events;

        var snapshot = eventRepository.fetchLatestSnapshot(aggregateType, aggregateId);
        if (snapshot.isPresent()) {
            JsonNode baseState = objectMapper.readTree(snapshot.get().snapshot());
            events = eventRepository.fetchChangesAfterVersion(
                aggregateType, aggregateId, snapshot.get().version()
            );
            state = AggregateHydrationUtil.hydrate(baseState, events);
        } else {
            events = eventRepository.fetchChangesForAggregate(aggregateType, aggregateId);
            state = AggregateHydrationUtil.hydrate(events);
        }

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

        return state;
    }

    // -----------------------------------------------------------------------
    // JooqService
    // -----------------------------------------------------------------------

    @Override
    protected String getSchemaName() {
        return "platform-event";
    }
}
