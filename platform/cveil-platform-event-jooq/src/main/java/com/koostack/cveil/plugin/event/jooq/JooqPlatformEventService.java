package com.koostack.cveil.plugin.event.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.cveil.common.crypto.FieldEncryptor;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.Shreddable;
import com.koostack.cveil.core.event.models.AggregateSnapshotTrigger;
import com.koostack.cveil.core.event.models.PlatformEvent;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.exception.CveilServiceException;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.SubjectDsarService;
import com.koostack.cveil.plugin.jooq.common.JooqContextFactory;
import com.koostack.cveil.plugin.redis.common.JedisProvider;
import com.koostack.cveil.plugin.redis.common.RedisClientFactory;
import com.koostack.cveil.plugin.redis.common.RedisContextFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

/**
 * Unified jOOQ-backed event service — durable event store for all domain events (platform +
 * tenant).
 *
 * <p>Events are appended to a single {@code event} table with optimistic concurrency control via
 * per-aggregate versioning. A unique constraint on {@code (tenant_id, aggregate_type, aggregate_id,
 * version)} prevents duplicate writes.
 *
 * <p>Supports the digital shredder pattern: events implementing {@link Shreddable} are encrypted
 * with per-subject DSAR keys before persistence.
 */
public class JooqPlatformEventService implements EventService<PlatformEventJooqConfig> {

    private static final Logger log = LoggerFactory.getLogger(JooqPlatformEventService.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int SNAPSHOT_DEPTH_THRESHOLD = 20;

    private DSLContext dslContext;
    private EventRepository eventRepository;
    private RedisEventPublisher redisPublisher;
    private OutboxPoller outboxPoller;
    private JedisPooled signalJedis;
    private String nudgeStreamKey;
    private volatile SubjectDsarService<?> subjectDsarService;
    private volatile String encryptionKey;

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
                JooqContextFactory.createTenantAwareContext(config, "cveil-platform-event-jooq");

        this.eventRepository = new EventRepository(dslContext);

        if (config.redis() != null) {
            // Dedicated admin connection for the outbox nudge stream.
            // Bypasses tenant ACL so both writer and poller share the same stream.
            this.signalJedis =
                    RedisClientFactory.createClient(
                            config.redis().host(),
                            config.redis().port(),
                            config.redis().password(),
                            config.redis().tlsConfig());

            JedisProvider jedisProvider;
            if (config.tenantCredentialLookup() != null) {
                jedisProvider =
                        RedisContextFactory.createProvider(
                                config.redis(), config.tenantCredentialLookup());
            } else {
                jedisProvider = JedisProvider.shared(signalJedis);
            }
            this.redisPublisher =
                    new RedisEventPublisher(jedisProvider, "cveil", config.shardCount());
            this.nudgeStreamKey = this.redisPublisher.outboxNudgeStreamKey();

            this.outboxPoller =
                    new OutboxPoller(
                            dslContext, redisPublisher, signalJedis, config.outboxPollerCount());
            this.outboxPoller.start();
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

    /**
     * Injects the DSAR key repository and the admin encryption key used to decrypt per-subject DSAR
     * keys at publish time (digital shredder). Forwards the same references to the OutboxPoller for
     * decryption at relay time.
     */
    public void setSubjectDsarService(SubjectDsarService<?> service, String encryptionKey) {
        this.subjectDsarService = service;
        this.encryptionKey = encryptionKey;
        if (this.outboxPoller != null) {
            this.outboxPoller.setSubjectDsarService(service, encryptionKey);
        }
    }

    // -----------------------------------------------------------------------
    // EventService
    // -----------------------------------------------------------------------

    @Override
    public void publishEvent(PublishDomainEventInput input) {
        if (dslContext == null) {
            throw new CveilServiceException("JooqPlatformEventService is not configured");
        }
        TenantContext.runWhere(
                input.tenantId(),
                () -> {
                    try {
                        String aggregateType = input.key().aggregateType();
                        String aggregateId = input.key().aggregateId().toString();
                        String serializedPayload = objectMapper.writeValueAsString(input.payload());

                        boolean shreddable = false;
                        UUID dsarId = null;
                        if (input.payload() instanceof Shreddable s
                                && subjectDsarService != null
                                && encryptionKey != null) {
                            String subjectId = s.subjectIdentifier();
                            if (subjectId == null || subjectId.isBlank()) {
                                throw new CveilServiceException(
                                        "Shreddable event "
                                                + input.eventType()
                                                + " returned null/blank subjectIdentifier() —"
                                                + " cannot encrypt");
                            }
                            dsarId = SubjectDsarService.deriveDsarId(subjectId);
                            final UUID lookupId = dsarId;
                            String encryptedDsarKey =
                                    subjectDsarService
                                            .findKeyByDsarId(dsarId)
                                            .orElseThrow(
                                                    () ->
                                                            new CveilServiceException(
                                                                    "No DSAR key found for dsar_id="
                                                                            + lookupId
                                                                            + " — cannot encrypt"
                                                                            + " shreddable event "
                                                                            + input.eventType()));
                            String rawDsarKey =
                                    FieldEncryptor.decrypt(encryptedDsarKey, this.encryptionKey);
                            serializedPayload =
                                    objectMapper.writeValueAsString(
                                            FieldEncryptor.encrypt(serializedPayload, rawDsarKey));
                            shreddable = true;
                        }

                        final boolean isShreddable = shreddable;
                        final UUID eventDsarId = dsarId;
                        final String payload = serializedPayload;

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
                                                    payload);

                                    long eventId = txRepo.insert(event, isShreddable, eventDsarId);
                                    log.debug(
                                            "Published event {} for {}/{} (shreddable={})",
                                            eventId,
                                            aggregateType,
                                            aggregateId,
                                            isShreddable);

                                    if (version % SNAPSHOT_DEPTH_THRESHOLD == 0) {
                                        triggerSnapshot(aggregateType, aggregateId, version);
                                    }
                                });

                        if (signalJedis != null) {
                            try {
                                signalJedis.xadd(
                                        nudgeStreamKey,
                                        Map.of("t", input.tenantId()),
                                        new XAddParams().maxLen(1000));
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to nudge outbox stream — event will be relayed via"
                                                + " backlog drain",
                                        ex);
                            }
                        }
                    } catch (CveilServiceException e) {
                        throw e;
                    } catch (DataAccessException e) {
                        throw new CveilServiceException(
                                "Concurrent modification conflict for "
                                        + input.key().aggregateType()
                                        + "/"
                                        + input.key().aggregateId()
                                        + " — retry the operation",
                                e);
                    } catch (Exception e) {
                        throw new CveilServiceException(
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
            throw new CveilServiceException("JooqPlatformEventService is not configured");
        }
        try {
            return TenantContext.callWhere(
                    key.tenantId(),
                    () ->
                            eventRepository.getAggregateVersion(
                                    key.aggregateType(), key.aggregateId().toString()));
        } catch (CveilServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilServiceException(
                    "Failed to get aggregate version for "
                            + key.aggregateType()
                            + "/"
                            + key.aggregateId(),
                    e);
        }
    }

    @Override
    public <T extends AggregateRoot> T hydrateAggregate(AggregateKey key, Class<T> type) {
        if (dslContext == null) {
            throw new CveilServiceException("JooqPlatformEventService is not configured");
        }
        try {
            return TenantContext.callWhere(
                    key.tenantId(),
                    () -> {
                        var events =
                                eventRepository.fetchChangesForAggregate(
                                        key.aggregateType(), key.aggregateId().toString());
                        return PlatformAggregateEventApplier.replay(events, type);
                    });
        } catch (CveilServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new CveilServiceException(
                    "Failed to hydrate aggregate " + key.aggregateType() + "/" + key.aggregateId(),
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
