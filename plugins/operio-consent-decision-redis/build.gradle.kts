plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    // Internal Project Dependency
    api(project(":operio-core"))
    // Shared Redis infrastructure (Jedis)
    implementation(project(":plugins:operio-redis-common"))

    // Test — jedis-mock provides an in-process Redis server (no Docker required)
    testImplementation("com.github.fppt:jedis-mock:1.1.4")
    // SLF4J binding so logging doesn't spew warnings during tests
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
