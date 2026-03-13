plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    api(project(":cveil-core"))
    api(project(":platform:cveil-core-schema"))
    implementation(project(":common:cveil-jooq"))
}

// ---------------------------------------------------------------------------
// jOOQ code generation
// ---------------------------------------------------------------------------

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
    }
}

val jooqCodegen by configurations.creating

dependencies {
    jooqCodegen(project(":common:cveil-jooq"))
}

val h2DbFile = layout.buildDirectory.file("h2/platform-core")
val generatedDir = layout.buildDirectory.dir("generated-src/jooq/main")
val schemaProject = project(":platform:cveil-core-schema")

tasks.register<JavaExec>("generateJooq") {
    description = "Runs Flyway migrations against H2 and generates jOOQ sources for core"
    group = "jooq"

    classpath = jooqCodegen
    mainClass.set("com.koostack.cveil.plugin.jooq.common.JooqCodegenRunner")

    val h2Url = provider { "jdbc:h2:file:${h2DbFile.get().asFile.absolutePath}" }
    val migrationPath = provider { "${schemaProject.projectDir}/src/main/resources/db/migration/platform-core" }
    val outputPath = provider { generatedDir.get().asFile.absolutePath }

    argumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf(
            h2Url.get(), outputPath.get(), migrationPath.get(),
            "com.koostack.cveil.platform.core.jooq.generated"
        )
    })

    inputs.dir("${schemaProject.projectDir}/src/main/resources/db/migration")
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

tasks.named("compileJava") { dependsOn("generateJooq") }
tasks.named("delombok") { dependsOn("generateJooq") }
tasks.named("sourcesJar") { dependsOn("generateJooq") }
tasks.named("javadocJar") { dependsOn("generateJooq") }
