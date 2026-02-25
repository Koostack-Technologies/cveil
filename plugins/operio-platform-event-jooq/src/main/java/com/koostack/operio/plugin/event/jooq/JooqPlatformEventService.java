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
import com.koostack.operio.plugin.jooq.common.JooqDataSourceFactory;
import com.koostack.operio.plugin.jooq.common.JooqService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisContextFactory;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
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
        DataSource dataSource = JooqDataSourceFactory.createDataSource(
            config,
            "operio-platform-event-jooq"
        );

        String migrationLocation = resolveMigrationLocation(config.dialect());
        Flyway.configure()
            .dataSource(dataSource)
            .locations(migrationLocation)
            .table("flyway_schema_history_platform_event")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();

        SQLDialect sqlDialect = SQLDialect.valueOf(config.dialect().toUpperCase());
        Settings settings = new Settings().withRenderNameCase(RenderNameCase.LOWER);
        this.dslContext = DSL.using(dataSource, sqlDialect, settings);
        this.eventRepository = new EventRepository(dslContext);
        this.eventTypeCache = new EventTypeCache(eventRepository);

        if (config.redis() != null) {
            JedisProvider jedisProvider = RedisContextFactory.createSharedProvider(config.redis());
            this.redisPublisher = new RedisEventPublisher(jedisProvider, "operio");
            this.outboxPoller = new OutboxPoller(dslContext, redisPublisher);
            this.outboxPoller.start(2);
        }

        log.info(
            "JooqPlatformEventService configured (dialect={}, redis={})",
            config.dialect(),
            config.redis() != null ? "enabled" : "disabled"
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
        try {
            if (!eventTypeCache.isValid(input.eventType())) {
                throw new OperioServiceException(
                    "Unknown event type: " + input.eventType()
                );
            }

            U state = input.state();
            String aggregateType = state.aggregateType();
            String aggregateId = state.aggregateId().toString();

            JsonNode currentState = hydrateAggregate(aggregateType, aggregateId, input.tenantId());
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
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to publish event for " + input.state().aggregateType()
                    + "/" + input.state().aggregateId(),
                e
            );
        }
    }

    @Override
    public Long getAggregateVersion(AggregateKey key) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        return eventRepository.getAggregateVersion(key.aggregateType(), key.aggregateId().toString());
    }

    @Override
    public <U> U getAggregate(AggregateKey key, Class<U> type) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        try {
            JsonNode state = hydrateAggregate(key.aggregateType(), key.aggregateId().toString(), key.tenantId());
            return objectMapper.treeToValue(state, type);
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

    private JsonNode hydrateAggregate(String aggregateType, String aggregateId, UUID tenantId)
            throws Exception {
        String tid = tenantId.toString();
        JsonNode state;
        List<PlatformEvent> events;

        var snapshot = eventRepository.fetchLatestSnapshot(aggregateType, aggregateId, tid);
        if (snapshot.isPresent()) {
            JsonNode baseState = objectMapper.readTree(snapshot.get().snapshot());
            events = eventRepository.fetchChangesAfterVersion(
                aggregateType, aggregateId, tid, snapshot.get().version()
            );
            state = AggregateHydrationUtil.hydrate(baseState, events);
        } else {
            events = eventRepository.fetchChangesForAggregate(aggregateType, aggregateId, tid);
            state = AggregateHydrationUtil.hydrate(events);
        }

        int depth = events.size();
        if (depth >= SNAPSHOT_DEPTH_THRESHOLD) {
            AggregateKey key = new AggregateKey(
                UUID.fromString(aggregateId), aggregateType, tenantId
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
