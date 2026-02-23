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
 * Gradle 9.x compatibility note
 * ---------------------------------------------------------------------------
 * The `io.quarkus` Gradle plugin (3.22.x) is NOT applied here because it
 * creates detached configurations that extend `compileClasspath`, which
 * Gradle 9.x explicitly prohibits ("Extending a detachedConfiguration is
 * not allowed").  Once the Quarkus team ships a Gradle-9-compatible plugin
 * release, re-enable it by uncommenting the plugin line below.
 *
 * Without the plugin the module compiles, tests run via JUnit 5, and a
 * runnable Quarkus uber-jar can still be produced via the Quarkus CLI:
 *
 *     quarkus build --gradle
 *
 * or by invoking the Quarkus Maven plugin wrapper if needed.  Dev mode
 * (`quarkusDev`) and native-image builds require the plugin or the CLI.
 * ---------------------------------------------------------------------------
 */

plugins {
    id("buildlogic.java-application-conventions")
    // id("io.quarkus") version "3.22.3"  // TODO: re-enable when Quarkus supports Gradle 9.x
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

    // -- Operio core (framework-agnostic) -------------------------------------
    implementation(project(":operio-core"))
    implementation(project(":operio-bundle-core"))
    implementation(project(":operio-bundle-embedded"))
    implementation(project(":plugins:operio-platform-jooq"))
    implementation(project(":plugins:operio-consent-admin-jooq"))
    implementation(project(":plugins:operio-consent-decision-redis"))  // RedisConsentDecisionServiceConfig used by OperioProducers
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))

    // -- JDBC drivers (runtime only) ------------------------------------------
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    runtimeOnly("com.h2database:h2:2.2.224")

    // -- Test -----------------------------------------------------------------
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    testImplementation("net.bytebuddy:byte-buddy:1.17.5")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.17.5")
}

// ---------------------------------------------------------------------------
// Force Byte Buddy 1.17.5 for JDK 25 classfile support (version 69).
// The Quarkus BOM (enforcedPlatform) pins Byte Buddy to 1.15.11 which
// cannot read JDK 25 classfiles, causing Mockito to fail at runtime with
// IllegalArgumentException in OpenedClassReader.  This resolution strategy
// overrides the BOM constraint for test configurations only.
// ---------------------------------------------------------------------------
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy {
        force("net.bytebuddy:byte-buddy:1.17.5")
        force("net.bytebuddy:byte-buddy-agent:1.17.5")
    }
}

application {
    mainClass.set("io.quarkus.runner.GeneratedMain")
}
