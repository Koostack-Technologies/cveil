plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------
dependencies {
    // --- Runtime / API -------------------------------------------------------
    api(project(":operio-core"))
    implementation(project(":plugins:operio-jooq-common"))
    implementation(project(":plugins:operio-redis-common"))
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // Needs access to consent migrations for TenantManager.provisionTenant()
    implementation(project(":plugins:operio-consent-admin-jooq"))

    // --- Test ----------------------------------------------------------------
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("com.github.fppt:jedis-mock:1.1.4")
}

// ---------------------------------------------------------------------------
// jOOQ code generation task
// ---------------------------------------------------------------------------
sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
    }
}

val jooqCodegen by configurations.creating

dependencies {
    jooqCodegen(project(":plugins:operio-jooq-common"))
}

val h2DbFile = layout.buildDirectory.file("h2/platform")
val generatedDir = layout.buildDirectory.dir("generated-src/jooq/main")

tasks.register<JavaExec>("generateJooq") {
    description = "Runs Flyway migrations against a build-time H2 instance and generates jOOQ sources"
    group = "jooq"

    classpath = jooqCodegen
    mainClass.set("com.koostack.operio.plugin.jooq.common.JooqCodegenRunner")

    val h2Url = provider {
        "jdbc:h2:file:${h2DbFile.get().asFile.absolutePath}"
    }
    val migrationPath = provider {
        "${projectDir}/src/main/resources/db/migration/platform-event"
    }
    val outputPath = provider {
        generatedDir.get().asFile.absolutePath
    }

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(h2Url.get(), outputPath.get(), migrationPath.get(), "com.koostack.operio.plugin.event.jooq.generated")
    })

    inputs.dir("${projectDir}/src/main/resources/db/migration/platform-event")
    outputs.dir(generatedDir)

    doFirst {
        val h2Dir = h2DbFile.get().asFile.parentFile
        if (h2Dir.exists()) h2Dir.deleteRecursively()
        val genDir = generatedDir.get().asFile
        if (genDir.exists()) genDir.deleteRecursively()
        h2Dir.mkdirs()
        genDir.mkdirs()
    }
}

tasks.named("compileJava") {
    dependsOn("generateJooq")
}

tasks.named("delombok") {
    dependsOn("generateJooq")
}

tasks.named("sourcesJar") {
    dependsOn("generateJooq")
}

tasks.named("javadocJar") {
    dependsOn("generateJooq")
}
