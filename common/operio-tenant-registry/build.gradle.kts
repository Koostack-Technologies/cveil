plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    implementation(project(":common:operio-redis"))

    // In-process cache for active tenant set
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JSON parsing for event payloads
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    implementation("org.slf4j:slf4j-api:2.0.16")

    // --- Test ----------------------------------------------------------------
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
    testImplementation("com.github.fppt:jedis-mock:1.1.4")
}
