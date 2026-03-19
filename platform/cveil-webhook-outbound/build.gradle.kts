plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":cveil-core"))
    implementation(project(":common:cveil-jooq"))
    implementation(project(":common:cveil-redis"))
    implementation(project(":platform:cveil-platform-event-jooq"))
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Resilience4j — retry with exponential backoff and per-endpoint circuit breaking
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
}
