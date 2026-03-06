plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation(project(":platform:operio-core-schema"))
}
