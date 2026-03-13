plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

val keycloakVersion = "26.0.8"

dependencies {
    api(project(":operio-core"))
    implementation(project(":platform:operio-platform-keycloak"))
    implementation("org.keycloak:keycloak-admin-client:$keycloakVersion")
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
