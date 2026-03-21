/*
 * Cveil Site — Admin Platform & Dashboard
 *
 * Built on Quarkus for the admin dashboard, public website, IAM, billing, and
 * tenant management.  Separate deployable from the core REST API.
 *
 * Virtual threads MUST be enabled (quarkus.virtual-threads.enabled=true) so
 * that ScopedValue-based TenantContext and RequestContext work correctly.
 */

plugins {
    id("buildlogic.java-application-conventions")
    id("io.quarkus") version "3.22.3"
}

dependencies {
    // -- Quarkus BOM ----------------------------------------------------------
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.22.3"))

    // -- Quarkus core ---------------------------------------------------------
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-rest-qute")

    // -- Cveil core (framework-agnostic) -------------------------------------
    implementation(project(":cveil-core"))
    implementation(project(":cveil-bundle-core"))
    implementation(project(":common:cveil-crypto"))
    implementation(project(":platform:cveil-platform-keycloak"))
    implementation("org.keycloak:keycloak-admin-client:26.0.8")
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")
    implementation(project(":common:cveil-jooq"))
    implementation(project(":common:cveil-redis"))

    // -- Site Redis projection (own read model) -------------------------------
    implementation(project(":platform:cveil-site-projection-redis"))

    // -- Platform event infrastructure ----------------------------------------
    implementation(project(":platform:cveil-platform-event-jooq"))

    // -- Write-path: site dispatches commands to these domains -----------------
    implementation(project(":platform:cveil-core-command"))
    implementation(project(":platform:cveil-billing-command"))
    implementation(project(":platform:cveil-iam-command"))
    implementation(project(":platform:cveil-payment-proc-stripe"))

    // -- Site needs consent admin command for DeferredProvisioningTask ---------
    implementation(project(":tenant:cveil-consent-admin-command"))
    implementation(project(":tenant:cveil-consent-admin-jooq-common"))
    implementation(project(":tenant:cveil-consent-admin-query-jooq"))
    implementation(project(":tenant:cveil-consent-admin-projection-jooq"))
    implementation(project(":tenant:cveil-consent-decision-projection-redis"))

    // -- Platform SQL projection handlers (site runs these projections) --------
    implementation(project(":platform:cveil-core-projection-jooq"))
    implementation(project(":platform:cveil-billing-projection-jooq"))
    implementation(project(":platform:cveil-iam-projection-jooq"))

    // -- SQL query services (needed during migration, replaced by Redis later) -
    implementation(project(":platform:cveil-core-query-jooq"))
    implementation(project(":platform:cveil-billing-query-jooq"))
    implementation(project(":platform:cveil-iam-query"))
    implementation(project(":platform:cveil-plan-query-jooq"))

    // -- Metrics modules (publisher, projection, sweeper) ---------------------
    implementation(project(":platform:cveil-platform-metrics-redis"))
    implementation(project(":common:cveil-metrics-worker"))

    // -- Platform schemas (Flyway migrations owned by site) -------------------
    implementation(project(":platform:cveil-core-schema"))
    implementation(project(":platform:cveil-iam-schema"))
    implementation(project(":platform:cveil-billing-schema"))

    // -- JDBC drivers (runtime only) ------------------------------------------
    runtimeOnly("org.postgresql:postgresql:42.7.5")

    // -- Test -----------------------------------------------------------------
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    testRuntimeOnly("com.h2database:h2:2.2.224")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

application {
    mainClass.set("io.quarkus.runner.GeneratedMain")
}
