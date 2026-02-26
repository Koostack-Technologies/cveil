package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * Base test class that provides PostgreSQL and Redis containers for
 * end-to-end testing of {@link JooqPlatformEventService}.
 *
 * <p>Subclasses get a fully configured {@link JooqPlatformEventService}
 * backed by real PostgreSQL and Redis instances via Testcontainers.
 */
@Testcontainers
abstract class PlatformEventServiceTestBase {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("operio_event_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static JooqPlatformEventService service;
    static DSLContext dsl;

    @BeforeAll
    static void configureService() {
        // Provide a noop credential lookup — the test Redis container has no
        // ACL users, so the TenantAwareJedisProvider falls back to the admin
        // pool (default user) when no TenantContext is active.
        TenantAwareJedisProvider.TenantCredentialLookup noopLookup =
            tenantId -> null;

        PlatformEventJooqConfig config = new PlatformEventJooqConfig(
            postgres.getJdbcUrl(),
            "POSTGRES",
            postgres.getUsername(),
            postgres.getPassword(),
            new TestRedisConfig(redis.getHost(), redis.getMappedPort(6379), null),
            noopLookup,
            List::of
        );

        service = new JooqPlatformEventService();
        service.configure(config);

        Settings testSettings = new Settings()
            .withRenderNameCase(RenderNameCase.LOWER)
            .withRenderSchema(false);
        dsl = DSL.using(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        dsl = DSL.using(dsl.configuration().derive(testSettings));
    }
}
