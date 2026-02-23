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

    // --- Code-generation tooling (used by JooqCodegenRunner at build time) ---
    implementation("org.jooq:jooq-meta:3.19.5")
    implementation("org.jooq:jooq-codegen:3.19.5")
    implementation("com.h2database:h2:2.2.224")
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    implementation("org.glassfish.jaxb:jaxb-runtime:4.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- Test ----------------------------------------------------------------
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
