plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    // Redis client
    api("redis.clients:jedis:5.2.0")
}
