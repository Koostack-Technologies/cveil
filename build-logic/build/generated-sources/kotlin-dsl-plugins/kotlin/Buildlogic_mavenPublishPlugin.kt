/**
 * Precompiled [buildlogic.maven-publish.gradle.kts][Buildlogic_maven_publish_gradle] script plugin.
 *
 * @see Buildlogic_maven_publish_gradle
 */
public
class Buildlogic_mavenPublishPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Buildlogic_maven_publish_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
