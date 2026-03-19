plugins {
    id("java")
    id("application")
}

group = "com.koostack"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":sdks:cveil-sdk-java"))
}

application {
    mainClass.set("com.koostack.cveil.examples.ExampleRunner")
}

// Run all examples in sequence
tasks.named<JavaExec>("run") {
    description = "Run all cveil SDK examples in sequence"
}

// Individual example tasks
mapOf(
    "runCatalogue"  to "com.koostack.cveil.examples.CatalogueSetup",
    "runGovernance"  to "com.koostack.cveil.examples.GovernanceSetup",
    "runSubjects"    to "com.koostack.cveil.examples.SubjectManagement",
    "runConsent"     to "com.koostack.cveil.examples.ConsentLifecycle",
    "runMasking"     to "com.koostack.cveil.examples.DataMasking",
    "runBatch"       to "com.koostack.cveil.examples.BatchOperations",
    "runDsar"        to "com.koostack.cveil.examples.DsarCompliance",
).forEach { (taskName, mainCls) ->
    tasks.register<JavaExec>(taskName) {
        group = "examples"
        description = "Run ${mainCls.substringAfterLast('.')}"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainCls)
    }
}