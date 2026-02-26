/*
 * Operio REST API — Multi-Tenant Cloud Consent Service
 *
 * Built on Quarkus for fast startup, low memory footprint, and native image
 * support.  This module is the cloud deployment REST API — it is NOT a Spring
 * Boot application.  The operio-core SPI layer is fully framework-agnostic,
 * so this Quarkus module is a thin JAX-RS adapter over the core library.
 *
 * Virtual threads MUST be enabled (quarkus.virtual-threads.enabled=true) so
 * that ScopedValue-based TenantContext and RequestContext work correctly.
 *
 * ---------------------------------------------------------------------------
 * Quarkus Gradle plugin note
 * ---------------------------------------------------------------------------
 * The `io.quarkus` Gradle plugin (3.22.3) is applied below.  It requires
 * Gradle 8.x — when the project briefly used Gradle 9.x, the plugin was
 * disabled due to detached-configuration errors.  The project currently uses
 * Gradle 8.14 (see gradle-wrapper.properties), so the plugin works correctly
 * and `quarkusBuild` produces the fast-jar layout.
 * ---------------------------------------------------------------------------
 */

plugins {
    id("buildlogic.java-application-conventions")
    id("io.quarkus") version "3.22.3"
}

dependencies {
    // -- Quarkus BOM ----------------------------------------------------------
    // Using enforcedPlatform so Quarkus dependency versions win over any
    // transitive version that might creep in from operio-core's dependency tree.
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.22.3"))

    // -- Quarkus core ---------------------------------------------------------
    implementation("io.quarkus:quarkus-rest-jackson")        // RESTEasy Reactive + Jackson
    implementation("io.quarkus:quarkus-hibernate-validator")  // Bean Validation (JSR 380)
    implementation("io.quarkus:quarkus-smallrye-health")     // MicroProfile Health
    implementation("io.quarkus:quarkus-smallrye-openapi")    // OpenAPI spec + Swagger UI
    implementation("io.quarkus:quarkus-config-yaml")         // YAML config support
    implementation("io.quarkus:quarkus-scheduler")           // @Scheduled jobs (counter reconciliation)

    // -- Operio core (framework-agnostic) -------------------------------------
    implementation(project(":operio-core"))
    implementation(project(":operio-bundle-core"))
    // The embedded bundle (H2 + Caffeine) is compile-only so it does NOT ship
    // in the production Docker image.  OperioProducers loads it via reflection
    // when operio.mode=embedded, giving a clear error if the classes are absent.
    // For local IDE development with mode=embedded, add the module to your run
    // configuration's classpath manually or switch to mode=production.
    compileOnly(project(":operio-bundle-embedded"))
    implementation(project(":plugins:operio-platform-jooq"))
    implementation(project(":tenant:operio-consent-admin-jooq"))
    implementation(project(":plugins:operio-consent-decision-redis"))  // RedisConsentDecisionServiceConfig used by OperioProducers
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))

    // -- JDBC drivers (runtime only) ------------------------------------------
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    // H2 is only needed for embedded mode (not production).  Kept as
    // testRuntimeOnly so integration tests that spin up EmbeddedConsentBundle
    // still work.  The production Docker image does not include H2.
    // runtimeOnly("com.h2database:h2:2.2.224")  // removed from production

    // -- Test -----------------------------------------------------------------
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    // Embedded bundle + H2 available in tests for embedded-mode coverage
    testImplementation(project(":operio-bundle-embedded"))
    testRuntimeOnly("com.h2database:h2:2.2.224")

    // -- Testcontainers (Postgres + Redis for production-mode integration tests)
    // Redis uses GenericContainer directly — no extra Redis-specific module needed.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

application {
    mainClass.set("io.quarkus.runner.GeneratedMain")
}
