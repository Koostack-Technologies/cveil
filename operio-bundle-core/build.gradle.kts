plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes PluginBundle and core models
    api(project(":operio-core"))

    // The plugins this bundle wires
    implementation(project(":tenant:operio-consent-admin-jooq"))
    implementation(project(":plugins:operio-consent-decision-redis"))
    implementation(project(":plugins:operio-discovery-sensitive-annotation"))

    // Transitive dependencies needed for config type resolution
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))
}
