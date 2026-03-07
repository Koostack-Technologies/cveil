plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":platform:operio-iam-command"))
    implementation(project(":platform:operio-platform-keycloak"))
    implementation(project(":common:operio-jooq"))
    implementation(project(":common:operio-redis"))
    implementation(project(":tenant:operio-consent-admin-jooq-common"))
    implementation("org.slf4j:slf4j-api:2.0.16")
}
