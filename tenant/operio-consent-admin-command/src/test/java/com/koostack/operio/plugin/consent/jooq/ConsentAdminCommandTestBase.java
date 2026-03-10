package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.core.models.AggregateRoot;
import com.koostack.operio.plugin.event.jooq.EventRepository;
import com.koostack.operio.plugin.event.jooq.JooqPlatformEventService;
import com.koostack.operio.plugin.event.jooq.PlatformAggregateEventApplier;
import com.koostack.operio.plugin.event.jooq.PlatformEventJooqConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Base test class providing PostgreSQL + Redis containers and a fully configured {@link
 * JooqConsentAdminCommandService} backed by a real {@link JooqPlatformEventService}.
 *
 * <p>The Redis container is started with a per-tenant ACL user ({@code t_test_tenant}) so the
 * outbox poller can authenticate and publish events. A background consumer thread captures all
 * events published to the sharded Redis stream for the test tenant.
 */
@Testcontainers
abstract class ConsentAdminCommandTestBase {

    static final String TEST_TENANT = "test_tenant";

    /** Stream key for the test tenant, derived from the shard function. */
    static final String STREAM_KEY = "operio:stream:tenant:" + TEST_TENANT;

    private static final String TENANT_ACL_USER = "t_" + TEST_TENANT;
    private static final String TENANT_ACL_PASS = "testpass";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("operio_consent_cmd_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand(
                            "redis-server",
                            "--user",
                            "default",
                            "on",
                            "nopass",
                            "~*",
                            "&*",
                            "+@all",
                            "--user",
                            TENANT_ACL_USER,
                            "on",
                            ">" + TENANT_ACL_PASS,
                            "~*",
                            "&*",
                            "+@all");

    static JooqPlatformEventService eventService;
    static JooqConsentAdminCommandService commandService;
    static DSLContext dsl;
    static JedisPooled testJedis;

    /** All events consumed from Redis by the background consumer thread. */
    static final CopyOnWriteArrayList<Map<String, String>> consumedEvents =
            new CopyOnWriteArrayList<>();

    private static volatile boolean consumerRunning;

    @BeforeAll
    static void configureServices() {
        // Credential lookup returns ACL credentials for the test tenant
        TenantAwareJedisProvider.TenantCredentialLookup credentialLookup =
                tenantId ->
                        new TenantAwareJedisProvider.TenantRedisCredentials(
                                "t_" + tenantId, TENANT_ACL_PASS);

        PlatformEventJooqConfig eventConfig =
                new PlatformEventJooqConfig(
                        postgres.getJdbcUrl(),
                        "POSTGRES",
                        postgres.getUsername(),
                        postgres.getPassword(),
                        new TestRedisConfig(redis.getHost(), redis.getMappedPort(6379), null),
                        credentialLookup,
                        () -> List.of(TEST_TENANT));

        eventService = new JooqPlatformEventService();
        eventService.configure(eventConfig);

        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl =
                DSL.using(
                        dsl.configuration()
                                .derive(
                                        new org.jooq.conf.Settings()
                                                .withRenderNameCase(
                                                        org.jooq.conf.RenderNameCase.LOWER)
                                                .withRenderSchema(false)));

        // Create the per-tenant schemas and run event store migrations.
        // In production this is handled by TenantManager; tests must do it explicitly.
        String base = "t_" + TEST_TENANT;
        String eventSchema = base + "_event";
        dsl.execute("CREATE SCHEMA IF NOT EXISTS " + base);
        dsl.execute("CREATE SCHEMA IF NOT EXISTS " + eventSchema);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/tenant-event-postgresql")
                .schemas(eventSchema)
                .table("flyway_schema_history_tenant_event")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        // Point the test DSL at the tenant schemas so assertions can find the tables.
        dsl.execute("SET search_path TO " + base + ", " + eventSchema + ", public");

        // -- Command service under test --
        commandService = new JooqConsentAdminCommandService();
        commandService.configure(new ConsentAdminCommandConfig(eventService));

        // -- Redis consumer for verification --
        testJedis = new JedisPooled(redis.getHost(), redis.getMappedPort(6379));
        startRedisConsumer();
    }

    @AfterAll
    static void tearDown() {
        consumerRunning = false;
        if (eventService != null) {
            eventService.stop();
        }
        if (testJedis != null) {
            testJedis.close();
        }
    }

    /**
     * Starts a background thread that continuously reads from the Redis Stream and appends consumed
     * entries to {@link #consumedEvents}.
     */
    private static void startRedisConsumer() {
        consumedEvents.clear();
        consumerRunning = true;
        Thread consumerThread =
                new Thread(
                        () -> {
                            StreamEntryID lastId = new StreamEntryID();
                            while (consumerRunning) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    var results =
                                            testJedis.xread(
                                                    XReadParams.xReadParams().count(100).block(200),
                                                    Map.of(STREAM_KEY, lastId));
                                    if (results != null) {
                                        for (var streamResult : results) {
                                            for (StreamEntry entry : streamResult.getValue()) {
                                                consumedEvents.add(entry.getFields());
                                                lastId = entry.getID();
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    if (consumerRunning) {
                                        try {
                                            Thread.sleep(50);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    }
                                }
                            }
                        },
                        "test-redis-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /**
     * Waits (with timeout) until at least {@code expected} events have been consumed from Redis.
     */
    static void awaitConsumedEvents(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (consumedEvents.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    /** Returns consumed events filtered by the given event type. */
    static List<Map<String, String>> consumedByType(String eventType) {
        return consumedEvents.stream()
                .filter(e -> eventType.equals(e.get(TenantEvent.FIELD_EVENT_TYPE)))
                .toList();
    }

    /**
     * Replays all events for the given aggregate directly via {@link AggregateEventApplier},
     * bypassing the removed {@code EventService.getAggregate()} method.
     */
    @SuppressWarnings("unchecked")
    static <T extends AggregateRoot> T hydrateAggregate(
            String aggregateType, java.util.UUID aggregateId, Class<T> type) {
        EventRepository repo = new EventRepository(dsl);
        var events = repo.fetchChangesForAggregate(aggregateType, aggregateId.toString());
        return (T) PlatformAggregateEventApplier.replay(events, type);
    }
}
