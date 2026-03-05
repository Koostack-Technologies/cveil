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
    implementation(project(":common:operio-jooq"))
    implementation(project(":common:operio-redis"))
    implementation(project(":platform:operio-platform-jooq"))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // --- Test ----------------------------------------------------------------
    testImplementation("org.testcontainers:testcontainers:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("redis.clients:jedis:5.2.0")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
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
    jooqCodegen(project(":common:operio-jooq"))
}

val h2DbFile = layout.buildDirectory.file("h2/tenant-event")
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
        "${projectDir}/src/main/resources/db/migration/tenant-event"
    }
    val outputPath = provider {
        generatedDir.get().asFile.absolutePath
    }

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(h2Url.get(), outputPath.get(), migrationPath.get(), "com.koostack.operio.tenant.event.jooq.generated")
    })

    inputs.dir("${projectDir}/src/main/resources/db/migration/tenant-event")
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
