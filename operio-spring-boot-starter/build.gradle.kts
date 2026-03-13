/*
 * Operio Spring Boot Starter — Client SDK
 *
 * This is a *library* (starter) consumed by Spring Boot applications, not a
 * runnable application itself.  The Spring Boot Gradle plugin is applied solely
 * for BOM / dependency-management convenience; the bootJar task is disabled so
 * that a plain library jar is produced instead of a fat executable jar.
 *
 * What is NOT in this starter: REST controllers, auth filters, rate limiting,
 * CORS, DTOs, exception handlers, platform admin wiring, bootstrap key logic.
 * All of that lives in the Quarkus REST API module (operio-rest-api).
 */

plugins {
    id("buildlogic.java-library-conventions")
    id("org.springframework.boot") version "4.0.2"
    // Still necessary for property overrides and better BOM handling
    id("io.spring.dependency-management") version "1.1.7"
}

// ---------------------------------------------------------------------------
// This is a library, not a bootable application — disable the fat-jar task
// and ensure the standard jar is produced.
// ---------------------------------------------------------------------------
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    // -- Public API surface ---------------------------------------------------
    // Consumers of the starter need direct access to Operio, ConsentScope,
    // IdentityContext, annotations, exceptions, etc.
    api(project(":operio-core"))

    // -- Spring Boot infrastructure ------------------------------------------
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // -- Utility --------------------------------------------------------------
    implementation("org.apache.commons:commons-text")

    // -- Optional at runtime: identity plugin for Spring Security users -------
    // The auto-configuration references IdentitySpringSecurity via SPI /
    // reflection.  Users who want Spring Security-based identity resolution
    // add this to their own runtime classpath.
    compileOnly(project(":sdk:operio-identity-spring-security"))

    // -- Optional at runtime: consent bundles --------------------------------
    // The auto-configuration instantiates bundle classes reflectively so the
    // starter only needs compile-time visibility.  Users pick exactly one
    // bundle at runtime:
    //   operio-bundle-embedded  → H2 + Caffeine  (dev / testing default)
    //   operio-bundle-core      → Postgres + Redis (production)
    compileOnly(project(":operio-bundle-core"))
    // TODO: re-enable once operio-consent-sync-embedded is fixed
    // compileOnly(project(":operio-bundle-embedded"))
}
