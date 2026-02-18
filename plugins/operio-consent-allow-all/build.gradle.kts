plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    // TODO: Add specific implementation dependencies (e.g., Jackson, Spring Security, SLF4J)
}
