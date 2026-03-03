plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":common:operio-redis"))
    implementation(project(":common:operio-jooq"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
