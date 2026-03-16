plugins {
    id("java")
}

group = "com.koostack"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(project(":sdks:cveil-sdk-java"))
}

tasks.test {
    useJUnitPlatform()
}