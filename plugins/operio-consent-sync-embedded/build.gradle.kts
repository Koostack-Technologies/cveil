plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes ConsentSyncService and core models
    api(project(":operio-core"))

    // Internal delegates — not exposed to consumers
    implementation(project(":plugins:operio-consent-admin-jooq"))
    implementation(project(":plugins:operio-consent-decision-caffeine"))
    implementation(project(":plugins:operio-jooq-common"))

    // SLF4J API for catch-and-log error handling
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
