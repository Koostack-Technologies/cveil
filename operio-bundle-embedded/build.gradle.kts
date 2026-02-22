plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes PluginBundle and core models
    api(project(":operio-core"))

    // The plugins this bundle wires
    implementation(project(":plugins:operio-consent-admin-jooq"))
    implementation(project(":plugins:operio-consent-decision-caffeine"))
    implementation(project(":plugins:operio-consent-sync-embedded"))
    implementation(project(":plugins:operio-discovery-sensitive-annotation"))

    // Transitive dependencies needed for config type resolution
    implementation(project(":plugins:operio-jooq-common"))

    // Test
    testImplementation("com.h2database:h2:2.2.224")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
