plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":cveil-core"))

    // Spring Boot BOM for version management (matches cveil-spring-boot-starter)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))

    // Core Spring Security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // OAuth2 / JWT support (future-proofing for Okta, Azure AD, etc.)
    implementation("org.springframework.security:spring-security-oauth2-client")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
}
