plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":operio-core"))
    implementation(project(":platform:operio-iam-command"))
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))
    implementation(project(":tenant:operio-consent-admin-jooq-common"))
    implementation("org.slf4j:slf4j-api:2.0.16")
}
