plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

val stripeVersion = "28.2.0"

dependencies {
    api(project(":cveil-core"))

    implementation("com.stripe:stripe-java:$stripeVersion")

    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}
