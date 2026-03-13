plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes PluginBundle and core models
    api(project(":cveil-core"))

    // The plugins this bundle wires
    implementation(project(":tenant:cveil-consent-admin-jooq-common"))
    implementation(project(":plugins:cveil-consent-decision-caffeine"))
    implementation(project(":plugins:cveil-consent-sync-embedded"))
    implementation(project(":sdk:cveil-discovery-sensitive-annotation"))

    // Transitive dependencies needed for config type resolution
    implementation(project(":common:cveil-jooq"))

    // Test
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
