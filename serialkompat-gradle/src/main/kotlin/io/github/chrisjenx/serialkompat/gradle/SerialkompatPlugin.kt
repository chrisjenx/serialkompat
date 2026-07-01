package io.github.chrisjenx.serialkompat.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the serialkompat compatibility-gate tasks on a project.
 *
 * v0 registers `serialkompatCheck` and wires it into the `check` lifecycle so it
 * runs on every `./gradlew check` and on CI. The check's logic is implemented
 * incrementally (see the project issues); this class establishes the task wiring.
 */
public class SerialkompatPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Ensure the `check` lifecycle task exists even on projects without a JVM plugin.
        target.pluginManager.apply("lifecycle-base")

        val check =
            target.tasks.register(CHECK_TASK_NAME) { task ->
                task.group = "verification"
                task.description =
                    "Checks @Serializable models for backward/forward-incompatible JSON wire changes."
            }

        target.tasks.named("check").configure { it.dependsOn(check) }
    }

    public companion object {
        /** Name of the task that runs the compatibility gate. */
        public const val CHECK_TASK_NAME: String = "serialkompatCheck"
    }
}
