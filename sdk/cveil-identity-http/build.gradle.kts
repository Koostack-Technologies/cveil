plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":cveil-core"))

    // Spring Boot BOM for version management (matches cveil-spring-boot-starter)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))

    // Spring Web for RequestContextHolder / ServletRequestAttributes
    implementation("org.springframework:spring-web")

    // Servlet API for HttpServletRequest
    compileOnly("jakarta.servlet:jakarta.servlet-api")
}
