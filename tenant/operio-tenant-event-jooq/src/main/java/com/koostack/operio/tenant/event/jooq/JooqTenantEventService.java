package com.koostack.operio.tenant.event.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.core.event.TenantEventService;
import com.koostack.operio.core.event.models.AggregateSnapshotTrigger;
import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
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
 * jOOQ-backed {@link TenantEventService} implementation — durable event
 * store for tenant-scoped domain events.
 *
 * <p>Events are appended to an append-only {@code event} table with
 * optimistic concurrency control via per-aggregate versioning. A unique
 * constraint on {@code (aggregate_type, aggregate_id, version, tenant_id)}
 * prevents duplicate writes.
 */
public class JooqTenantEventService
    extends JooqService
    implements TenantEventService<TenantEventJooqConfig>
{

    private static final Logger log = LoggerFactory.getLogger(JooqTenantEventService.class);
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
        return "jooq-tenant-event";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<TenantEventJooqConfig> getConfigurationType() {
        return TenantEventJooqConfig.class;
    }

    @Override
    public void configure(TenantEventJooqConfig config) {
        String migrationLocation = resolveMigrationLocation(config.dialect());
        this.dslContext = JooqContextFactory.createTenantAwareContext(
            config,
            "operio-tenant-event-jooq",
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
            this.redisPublisher = new RedisEventPublisher(jedisProvider);
            this.outboxPoller = new OutboxPoller(dslContext, redisPublisher, config.tenantDiscovery());
            this.outboxPoller.start(2);
        }

        log.info(
            "JooqTenantEventService configured (dialect={}, redis={}, tenantAcl={})",
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
    // TenantEventService
    // -----------------------------------------------------------------------

    @Override
    public void publishEvent(PublishDomainEventInput input) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqTenantEventService is not configured");
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
                    TenantEvent event = new TenantEvent(
                        null,
                        input.tenantId(),
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
            throw new OperioServiceException("JooqTenantEventService is not configured");
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
            throw new OperioServiceException("JooqTenantEventService is not configured");
        }
        try {
            return TenantContext.callWhere(key.tenantId(), () -> {
                String aggregateType = key.aggregateType();
                String aggregateId = key.aggregateId().toString();
                List<TenantEvent> events;

                var snapshot = eventRepository.fetchLatestSnapshot(aggregateType, aggregateId);
                if (snapshot.isPresent()) {
                    U baseState = objectMapper.readValue(snapshot.get().snapshot(), type);
                    events = eventRepository.fetchChangesAfterVersion(
                        aggregateType, aggregateId, snapshot.get().version()
                    );
                    @SuppressWarnings("unchecked")
                    U result = (U) AggregateEventApplier.replay(
                        (AggregateRoot) baseState, events
                    );
                    triggerSnapshotIfNeeded(aggregateType, aggregateId, events);
                    return result;
                } else {
                    events = eventRepository.fetchChangesForAggregate(aggregateType, aggregateId);
                    @SuppressWarnings("unchecked")
                    Class<? extends AggregateRoot> rootType = (Class<? extends AggregateRoot>) type;
                    @SuppressWarnings("unchecked")
                    U result = (U) AggregateEventApplier.replay(events, rootType);
                    triggerSnapshotIfNeeded(aggregateType, aggregateId, events);
                    return result;
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
            throw new OperioServiceException("JooqTenantEventService is not configured");
        }
        eventRepository.insertEventType(input);
        eventTypeCache.refresh();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void triggerSnapshotIfNeeded(
        String aggregateType, String aggregateId, List<TenantEvent> events
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
        return "tenant-event";
    }
}
