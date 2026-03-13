plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

val keycloakVersion = "26.0.8"

dependencies {
    api(project(":operio-core"))
    implementation(project(":platform:operio-platform-keycloak"))
    implementation(project(":platform:operio-iam-schema"))
    implementation(project(":common:operio-jooq"))
    implementation("org.keycloak:keycloak-admin-client:$keycloakVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
