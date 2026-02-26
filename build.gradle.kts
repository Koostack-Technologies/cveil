tasks.register("resetLanguageServer") {
    group = "ide"
    description = "Regenerates Eclipse metadata for all subprojects and nukes the jdtls workspace cache"

    dependsOn(subprojects.mapNotNull { it.tasks.findByName("eclipse") })

    doLast {
        val home = System.getProperty("user.home")
        val cacheDir = file("$home/Library/Caches")
        if (cacheDir.exists()) {
            cacheDir.listFiles { f -> f.name.startsWith("jdtls-") }?.forEach { dir ->
                logger.lifecycle("Deleting jdtls cache: ${dir.absolutePath}")
                dir.deleteRecursively()
            }
            logger.lifecycle("jdtls cache cleared — restart the language server in your editor")
        } else {
            logger.lifecycle("No ~/Library/Caches directory found — skipping jdtls cache cleanup")
        }
    }
}
