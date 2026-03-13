plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":cveil-core"))
    implementation(project(":common:cveil-redis"))
    implementation(project(":common:cveil-jooq"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
