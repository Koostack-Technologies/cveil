plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes PluginBundle and core models
    api(project(":cveil-core"))

    // The plugins this bundle wires
    implementation(project(":tenant:cveil-consent-admin-jooq-common"))
    implementation(project(":tenant:cveil-consent-decision-projection-redis"))
    implementation(project(":sdk:cveil-discovery-sensitive-annotation"))

    // Transitive dependencies needed for config type resolution
    implementation(project(":common:cveil-jooq"))
    implementation(project(":common:cveil-redis"))
}
