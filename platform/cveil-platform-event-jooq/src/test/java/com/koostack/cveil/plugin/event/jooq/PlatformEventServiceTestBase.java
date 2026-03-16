package com.koostack.cveil.plugin.event.jooq;

import com.koostack.cveil.plugin.redis.common.TenantAwareJedisProvider;
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
                    .withDatabaseName("cveil_event_test")
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

        Settings testSettings =
                new Settings().withRenderNameCase(RenderNameCase.LOWER).withRenderSchema(false);
        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dsl.configuration().derive(testSettings));

        // Create unified event schema and run Flyway migrations — mirrors
        // what CveilProducers does in production.
        initUnifiedEventSchema();

        // Append cveilevent to search_path so the service resolves the
        // event and aggregate_snapshot tables without schema-qualification.
        String jdbcUrl = postgres.getJdbcUrl();
        String sep = jdbcUrl.contains("?") ? "&" : "?";
        String eventDbUrl = jdbcUrl + sep + "currentSchema=cveilevent,public";

        PlatformEventJooqConfig config =
                new PlatformEventJooqConfig(
                        eventDbUrl,
                        "POSTGRES",
                        postgres.getUsername(),
                        postgres.getPassword(),
                        new TestRedisConfig(redis.getHost(), redis.getMappedPort(6379), null),
                        noopLookup);

        service = new JooqPlatformEventService();
        service.configure(config);
    }

    /**
     * Creates the unified {@code cveilevent} schema and runs the platform-event Flyway migrations.
     * Mirrors what CveilProducers does in production.
     */
    static void initUnifiedEventSchema() {
        dsl.execute("CREATE SCHEMA IF NOT EXISTS cveilevent");
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/platform-event-postgresql")
                .schemas("cveilevent")
                .table("flyway_schema_history_event")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * Sets the test DSL search_path to the unified event schema so that assertion queries can find
     * the event and snapshot tables.
     */
    static void setTestSearchPath() {
        dsl.execute("SET search_path TO cveilevent, public");
    }
}
