plugins {
    id("buildlogic.java-library-conventions")
}

description = "Java 21 facade for the cveil-sdk-core WASM consent masking engine"

val jacksonVersion = "2.17.0"
val chicoryVersion = "1.1.0"

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
