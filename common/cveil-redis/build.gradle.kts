plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":cveil-core"))
    // Redis client
    api("redis.clients:jedis:5.2.0")
    // JSON serialization (used by pub/sub publisher/subscriber)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    // Logging (used by TenantAwareJedisProvider)
    implementation("org.slf4j:slf4j-api:2.0.16")

    // --- Test ----------------------------------------------------------------
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
    // jedis-mock provides in-process Redis server with pub/sub support (no Docker)
    testImplementation("com.github.fppt:jedis-mock:1.1.4")
    // Testcontainers + Toxiproxy for reconnection integration tests
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}
