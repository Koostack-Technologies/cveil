plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Public API — exposes ConsentDecisionService and core models
    api(project(":cveil-core"))

    // Caffeine in-memory cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // SLF4J binding so logging doesn't spew warnings during tests
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
