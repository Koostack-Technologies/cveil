package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.core.event.models.TenantEvent;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base test class providing PostgreSQL + Redis containers and a fully
 * configured {@link JooqTenantEventService} for integration testing.
 *
 * <p>A background consumer thread captures all events published to
 * the sharded Redis streams for verification.
 */
@Testcontainers
abstract class TenantEventServiceTestBase {

    static final String TEST_TENANT = "test_tenant";
    static final String STREAM_KEY = RedisEventPublisher.streamKeyForTenant(TEST_TENANT);
    private static final String TENANT_ACL_USER = "t_" + TEST_TENANT;
    private static final String TENANT_ACL_PASS = "testpass";

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("operio_tenant_event_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand(
                "redis-server",
                "--user", "default", "on", "nopass", "~*", "&*", "+@all",
                "--user", TENANT_ACL_USER, "on", ">" + TENANT_ACL_PASS,
                    "~*", "&*", "+@all"
            );

    static JooqTenantEventService service;
    static DSLContext dsl;
    static JedisPooled testJedis;

    /** All events consumed from Redis by the background consumer thread. */
    static final CopyOnWriteArrayList<Map<String, String>> consumedEvents =
        new CopyOnWriteArrayList<>();

    private static volatile boolean consumerRunning;

    @BeforeAll
    static void configureService() {
        TenantAwareJedisProvider.TenantCredentialLookup credentialLookup =
            tenantId -> new TenantAwareJedisProvider.TenantRedisCredentials(
                "t_" + tenantId, TENANT_ACL_PASS
            );

        TenantEventJooqConfig config = new TenantEventJooqConfig(
            postgres.getJdbcUrl(),
            "POSTGRES",
            postgres.getUsername(),
            postgres.getPassword(),
            new TestRedisConfig(redis.getHost(), redis.getMappedPort(6379), null),
            credentialLookup,
            () -> List.of(TEST_TENANT)
        );

        service = new JooqTenantEventService();
        service.configure(config);

        Settings testSettings = new Settings()
            .withRenderNameCase(RenderNameCase.LOWER)
            .withRenderSchema(false);
        dsl = DSL.using(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        dsl = DSL.using(dsl.configuration().derive(testSettings));

        testJedis = new JedisPooled(redis.getHost(), redis.getMappedPort(6379));
        startRedisConsumer();
    }

    @AfterAll
    static void tearDown() {
        consumerRunning = false;
        if (service != null) {
            service.stop();
        }
        if (testJedis != null) {
            testJedis.close();
        }
    }

    /**
     * Starts a background thread that reads from all sharded Redis streams
     * and appends consumed entries to {@link #consumedEvents}.
     */
    private static void startRedisConsumer() {
        consumedEvents.clear();
        consumerRunning = true;

        List<String> allKeys = RedisEventPublisher.allStreamKeys();
        Map<String, StreamEntryID> lastIds = new java.util.HashMap<>();
        for (String key : allKeys) {
            lastIds.put(key, new StreamEntryID());
        }

        Thread consumerThread = new Thread(() -> {
            while (consumerRunning) {
                try {
                    @SuppressWarnings("unchecked")
                    var results = testJedis.xread(
                        XReadParams.xReadParams().count(100).block(200),
                        lastIds
                    );
                    if (results != null) {
                        for (var streamResult : results) {
                            for (StreamEntry entry : streamResult.getValue()) {
                                consumedEvents.add(entry.getFields());
                                lastIds.put(streamResult.getKey(), entry.getID());
                            }
                        }
                    }
                } catch (Exception e) {
                    if (consumerRunning) {
                        try { Thread.sleep(50); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, "test-redis-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /**
     * Waits until at least {@code expected} events have been consumed.
     */
    static void awaitConsumedEvents(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (consumedEvents.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    /**
     * Returns consumed events filtered by event type.
     */
    static List<Map<String, String>> consumedByType(String eventType) {
        return consumedEvents.stream()
            .filter(e -> eventType.equals(e.get(TenantEvent.FIELD_EVENT_TYPE)))
            .toList();
    }

    /**
     * Returns consumed events filtered by tenant ID.
     */
    static List<Map<String, String>> consumedByTenant(String tenantId) {
        return consumedEvents.stream()
            .filter(e -> tenantId.equals(e.get(TenantEvent.FIELD_TENANT_ID)))
            .toList();
    }
}
