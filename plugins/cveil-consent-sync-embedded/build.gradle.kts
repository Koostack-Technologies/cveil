plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes core models
    api(project(":cveil-core"))

    // Internal delegates — not exposed to consumers
    implementation(project(":tenant:cveil-consent-admin-jooq-common"))
    implementation(project(":tenant:cveil-consent-admin-command"))
    implementation(project(":tenant:cveil-consent-admin-query-jooq"))
    implementation(project(":plugins:cveil-consent-decision-caffeine"))
    implementation(project(":common:cveil-jooq"))

    // SLF4J API for catch-and-log error handling
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
