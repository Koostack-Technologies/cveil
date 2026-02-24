plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    // Redis client
    api("redis.clients:jedis:5.2.0")
    // Logging (used by TenantAwareJedisProvider)
    implementation("org.slf4j:slf4j-api:2.0.16")

    // --- Test ----------------------------------------------------------------
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}
