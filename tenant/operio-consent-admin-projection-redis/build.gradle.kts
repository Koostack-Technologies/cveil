plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":tenant:operio-tenant-event-jooq"))
    implementation(project(":common:operio-redis"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // --- Test ----------------------------------------------------------------
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.github.fppt:jedis-mock:1.1.4")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
