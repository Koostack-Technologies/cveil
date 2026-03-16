plugins {
    id("buildlogic.java-library-conventions")
}

description = "Java 21 facade for the cveil-sdk-core WASM consent masking engine"

val jacksonVersion = "2.17.0"
val chicoryVersion = "1.1.0"

val wasmFileName = "cveil_sdk_core.wasm"
val cratesDir = rootProject.layout.projectDirectory.dir("crates")
val wasmOutputDir = layout.projectDirectory.dir("src/main/resources/wasm")

val buildWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile cveil-sdk-core Rust crate to WASI WASM"

    workingDir = cratesDir.asFile
    commandLine(
        "cargo", "build",
        "-p", "cveil-sdk-core",
        "--release",
        "--target", "wasm32-wasip1"
    )

    outputs.file(cratesDir.file("target/wasm32-wasip1/release/$wasmFileName"))
}

val copyWasm by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy compiled WASM binary into SDK resources"

    dependsOn(buildWasm)
    from(cratesDir.file("target/wasm32-wasip1/release/$wasmFileName"))
    into(wasmOutputDir)
}

tasks.named("processResources") {
    dependsOn(copyWasm)
}

dependencies {
    // JSON
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")

    // WASM runtime (pure-Java, WASI support)
    implementation("com.dylibso.chicory:wasm:$chicoryVersion")
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:wasi:$chicoryVersion")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
