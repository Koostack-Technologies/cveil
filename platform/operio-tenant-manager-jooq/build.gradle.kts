plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))
    implementation(project(":tenant:operio-consent-admin-jooq"))
    implementation("org.slf4j:slf4j-api:2.0.16")
}
