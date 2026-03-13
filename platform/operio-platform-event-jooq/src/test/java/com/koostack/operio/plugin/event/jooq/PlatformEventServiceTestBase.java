package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base test class that provides PostgreSQL and Redis containers for end-to-end testing of {@link
 * JooqPlatformEventService}.
 *
 * <p>Subclasses get a fully configured {@link JooqPlatformEventService} backed by real PostgreSQL
 * and Redis instances via Testcontainers.
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
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static JooqPlatformEventService service;
    static DSLContext dsl;

    @BeforeAll
    static void configureService() {
        // Provide a noop credential lookup — the test Redis container has no
        // ACL users, so the TenantAwareJedisProvider falls back to the admin
        // pool (default user) when no TenantContext is active.
        TenantAwareJedisProvider.TenantCredentialLookup noopLookup = tenantId -> null;

        PlatformEventJooqConfig config =
                new PlatformEventJooqConfig(
                        postgres.getJdbcUrl(),
                        "POSTGRES",
                        postgres.getUsername(),
                        postgres.getPassword(),
                        new TestRedisConfig(redis.getHost(), redis.getMappedPort(6379), null),
                        noopLookup);

        service = new JooqPlatformEventService();
        service.configure(config);

        Settings testSettings =
                new Settings().withRenderNameCase(RenderNameCase.LOWER).withRenderSchema(false);
        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dsl.configuration().derive(testSettings));

        // Create per-tenant schemas used by the test methods.
        // In production, OperioProducers creates t_platform and TenantManager
        // creates t_{tenantId}; tests must do this explicitly.
        for (String tenant : List.of("tenant_publish", "tenant_multi", "tenant_snap")) {
            createPlatformEventSchema(tenant);
        }
    }

    /**
     * Creates the per-tenant schemas and runs Flyway platform-event migrations. Mirrors what
     * OperioProducers does in production.
     */
    static void createPlatformEventSchema(String tenantId) {
        String base = "t_" + tenantId;
        String eventSchema = base + "_event";
        dsl.execute("CREATE SCHEMA IF NOT EXISTS " + base);
        dsl.execute("CREATE SCHEMA IF NOT EXISTS " + eventSchema);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/platform-event-postgresql")
                .schemas(eventSchema)
                .table("flyway_schema_history_platform_event")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * Sets the test DSL search_path to the given tenant's schemas so that assertion queries can
     * find the event and snapshot tables.
     */
    static void setTestSearchPath(String tenantId) {
        String base = "t_" + tenantId;
        dsl.execute("SET search_path TO " + base + ", " + base + "_event, public");
    }
}
