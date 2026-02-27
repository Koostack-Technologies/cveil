plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":tenant:operio-consent-admin-schema"))
    implementation(project(":tenant:operio-consent-admin-jooq-common"))
    implementation(project(":tenant:operio-tenant-event-jooq"))
    implementation(project(":plugins:operio-jooq-common"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}
