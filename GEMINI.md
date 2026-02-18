# Operio Project Context & Guidelines

## 1. Project Identity
* **Name:** Operio
* **Organization:** Koostack Technologies
* **Description:** A pluggable, consent-based sensitive data masking framework.
* **Core Philosophy:** "Safe by Default." Data is opaque unless explicit consent is granted via a loaded SPI implementation.

## 2. Technology Stack (Strict)
* **Java:** 25 (Temurin build 25.0.2). Use modern features (Switch expressions, Records, Virtual Threads).
* **Build Tool:** Gradle 9.3.1 (Kotlin DSL `build.gradle.kts`).
* **Framework:** Spring Boot 4.0.2.
* **AOP:** `spring-boot-starter-aspectj` (NOT `spring-boot-starter-aop`).

## 3. Package Structure & Naming
* **Base Package:** `com.koostack.operio`
* **Module Separation:**
    * **Core (`operio-core`):** Pure Java 25. No Spring dependencies.
        * `com.koostack.operio.core.service` (Interfaces like `MaskingService`)
        * `com.koostack.operio.core.spi` (Plugin contracts like `NamedPlugin`)
        * `com.koostack.operio.core.registry` (ServiceLoader logic)
        * `com.koostack.operio.core.annotation` (Annotations like `@Sensitive`)
    * **Spring Starter (`operio-spring-boot-starter`):** Spring integration code.
        * `com.koostack.operio.spring.config` (AutoConfiguration)
        * `com.koostack.operio.spring.aspect` (AspectJ Aspects)
        * `com.koostack.operio.spring.context` (Security Context bridges)
* **Naming Conventions:**
    * **DO NOT** use the word "Masking" in class names; use **Sensitive** (e.g., `SensitiveAspect`, `@Sensitive`).
    * **DO NOT** put the project name "Operio" in class names (e.g., use `ConsentService`, not `OperioConsentService`).

## 4. Architectural Patterns
* **Plugin System (SPI):**
    * Uses `java.util.ServiceLoader` encapsulated in `operio-core`.
    * **Registry:** `PluginRegistry` handles lookup.
    * **Resolution Strategy:**
        1.  **Exact Match:** Checks `getName()` (defaults to FQN) against input.
        2.  **Priority Fallback:** If no name match, sorts by `getPriority()` (descending).
* **AspectJ:**
    * Use `@Aspect` and `@Around`.
    * Must handle `ProceedingJoinPoint`.
    * Logic: `Check Consent` -> `If True, Return Raw` -> `Else, Return Masked`.

## 5. Coding Behavior Rules
* **Refactor vs. Create:** Always prefer updating an existing class or package over creating a new one if the logic fits.
* **FQN Default:** When implementing `NamedPlugin`, the default `getName()` must return `this.getClass().getName()`.
* **Java 25 Features:** Prefer `var`, `record` for DTOs, and `switch` expressions for logic branching.
* **Dependencies:** Never suggest `javax.*` packages; use `jakarta.*`.

## 6. Common Tasks & Snippets
* **Resolving a Plugin (in Core):**
    ```java
    ConsentService service = PluginRegistry.resolve(ConsentService.class, "preferredName");
    ```
* **Defining a Sensitive Method (in Consumer App):**
    ```java
    @Sensitive(category = "PII", provider = "okta")
    public String getEmail() { ... }
    ```