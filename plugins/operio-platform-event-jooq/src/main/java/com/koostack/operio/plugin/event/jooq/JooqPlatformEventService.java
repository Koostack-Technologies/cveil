package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.core.event.PlatformEventService;
import com.koostack.operio.core.event.models.PlatformEvent;
import com.koostack.operio.core.exception.OperioServiceException;
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

    private DSLContext dslContext;
    private EventRepository eventRepository;
    private RedisEventPublisher redisPublisher;

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

        if (config.redis() != null) {
            JedisProvider jedisProvider = RedisContextFactory.createSharedProvider(config.redis());
            this.redisPublisher = new RedisEventPublisher(jedisProvider, "operio");
        }

        log.info(
            "JooqPlatformEventService configured (dialect={}, redis={})",
            config.dialect(),
            config.redis() != null ? "enabled" : "disabled"
        );
    }

    @Override
    public boolean isReady() {
        return dslContext != null;
    }

    // -----------------------------------------------------------------------
    // PlatformEventService
    // -----------------------------------------------------------------------

    @Override
    public <U> void publish(PlatformEvent<U> event) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        try {
            String serializedPayload = event.payload() != null
                ? event.payload().toString()
                : null;
            long eventId = eventRepository.insert(event, serializedPayload);
            if (redisPublisher != null) {
                redisPublisher.publish(event, eventId, serializedPayload);
            }
            log.debug(
                "Published event {} for {}/{}",
                eventId, event.aggregateType(), event.aggregateId()
            );
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to publish event for " + event.aggregateType() + "/" + event.aggregateId(),
                e
            );
        }
    }

    @Override
    public Long getAggregateVersion(String aggregateType, String aggregateId) {
        if (dslContext == null) {
            throw new OperioServiceException("JooqPlatformEventService is not configured");
        }
        return eventRepository.getAggregateVersion(aggregateType, aggregateId);
    }

    // -----------------------------------------------------------------------
    // JooqService
    // -----------------------------------------------------------------------

    @Override
    protected String getSchemaName() {
        return "platform-event";
    }
}
