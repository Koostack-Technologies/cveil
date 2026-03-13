plugins {
    `maven-publish`
    signing
}

plugins.withId("java") {
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(project.provider { project.description })
                    url.set("https://github.com/koostacktech/cveil")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("koocodes")
                            name.set("Koostack Technologies")
                            email.set("koostacktech@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/koostacktech/cveil.git")
                        developerConnection.set("scm:git:ssh://github.com/koostacktech/cveil.git")
                        url.set("https://github.com/koostacktech/cveil")
                    }
                }
            }
        }
    }
}

publishing {
    repositories {
        maven {
            name = "OSSRH"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    if (!signingKey.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}