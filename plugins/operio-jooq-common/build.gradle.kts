plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    // jOOQ Open Source Edition
    api("org.jooq:jooq:3.19.5")
    // Standard JDBC/Persistence API
    api("jakarta.persistence:jakarta.persistence-api:3.1.0")
    // Connection pooling
    api("com.zaxxer:HikariCP:6.2.1")
    // Schema migration
    api("org.flywaydb:flyway-core:11.3.0")
    api("org.flywaydb:flyway-database-postgresql:11.3.0")

    // --- Code-generation tooling (used by JooqCodegenRunner at build time) ---
    implementation("org.jooq:jooq-meta:3.19.5")
    implementation("org.jooq:jooq-codegen:3.19.5")
    implementation("com.h2database:h2:2.2.224")
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    implementation("org.glassfish.jaxb:jaxb-runtime:4.0.5")
    // NOTE: slf4j-simple is intentionally NOT an implementation dependency.
    // The JooqCodegenRunner (which lives in this module's main source set)
    // is executed via the `jooqCodegen` configuration in downstream modules
    // (operio-consent-admin-jooq, operio-platform-jooq).  At codegen time,
    // SLF4J 2.x gracefully degrades to NOP if no binding is present — the
    // codegen output is unaffected.  At production runtime, the Quarkus
    // JBoss SLF4J binding is the correct provider; shipping slf4j-simple
    // as an `implementation` dependency causes "Class path contains multiple
    // SLF4J providers" warnings and could route logs to the wrong sink.

    // --- Test ----------------------------------------------------------------
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
