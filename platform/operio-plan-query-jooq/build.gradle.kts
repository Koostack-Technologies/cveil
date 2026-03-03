plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":common:operio-jooq"))
    implementation("org.slf4j:slf4j-api:2.0.16")
}
