package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.extractor.DiscoveryMode
import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerialkompatPluginTest {
    @Test
    fun `registers serialkompatCheck as a verification task`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val task = project.tasks.findByName(SerialkompatPlugin.CHECK_TASK_NAME)
        assertNotNull(task)
        assertTrue(task.group == "verification")
    }

    @Test
    fun `wires serialkompatCheck into the check lifecycle`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val check = project.tasks.getByName("check")
        val dependencyNames = check.taskDependencies.getDependencies(check).map { it.name }
        assertTrue(dependencyNames.contains(SerialkompatPlugin.CHECK_TASK_NAME))
    }

    @Test
    fun `registers serialkompatExtract and the serialkompat extension`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        assertNotNull(project.tasks.findByName(SerialkompatPlugin.EXTRACT_TASK_NAME))
        assertNotNull(project.extensions.findByName("serialkompat"))
    }

    @Test
    fun `discovery defaults to EXPLICIT`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val extension = project.extensions.getByType(SerialkompatExtension::class.java)
        assertEquals(DiscoveryMode.EXPLICIT, extension.discovery.get())
    }

    @Test
    fun `discovery mode can be configured`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val extension = project.extensions.getByType(SerialkompatExtension::class.java)
        extension.discovery.set(DiscoveryMode.OPT_OUT)
        assertEquals(DiscoveryMode.OPT_OUT, extension.discovery.get())
    }

    @Test
    fun `extract args skip discovery flags when types is non-empty, even with discovery configured`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val extension = project.extensions.getByType(SerialkompatExtension::class.java)
        extension.types.set(listOf("com.example.Foo"))
        extension.discovery.set(DiscoveryMode.OPT_OUT)

        val extract = project.tasks.getByName(SerialkompatPlugin.EXTRACT_TASK_NAME) as JavaExec
        val args = extract.argumentProviders.flatMap { it.asArguments() }

        assertFalse(args.contains("--discovery"), "unexpected --discovery in $args")
        assertFalse(args.contains("--scan-classes"), "unexpected --scan-classes in $args")
        assertTrue(args.contains("--types"), "expected --types in $args")
    }

    @Test
    fun `extract args omit --scan-classes when there is no Java or KMP source set to scan`() {
        // Neither the java plugin (Java `main` source set) nor a KMP plugin is applied here -- e.g.
        // a root/aggregator project -- so projectClassesDirs() resolves to an empty FileCollection.
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.chrisjenx.serialkompat")

        val extension = project.extensions.getByType(SerialkompatExtension::class.java)
        extension.discovery.set(DiscoveryMode.OPT_OUT)
        // types left empty -> discovery drives the scan.

        val extract = project.tasks.getByName(SerialkompatPlugin.EXTRACT_TASK_NAME) as JavaExec
        val args = extract.argumentProviders.flatMap { it.asArguments() }

        assertFalse(
            args.contains("--scan-classes"),
            "must not emit --scan-classes with no scan dirs (the extractor would see an empty " +
                "string and, with types also empty, throw IllegalArgumentException): $args",
        )
    }

    /**
     * The full end-to-end proof: a root/aggregator project (no Java, no KMP) with non-EXPLICIT
     * discovery and empty `types` must not crash `serialkompatExtract`. Omitting `--scan-classes`
     * (above) is necessary but not sufficient on its own — see [WorktreeSnapshotTest]-adjacent
     * note in the report; this also depends on the extractor accepting a discovery-only
     * invocation with neither `--types` nor `--scan-classes` (SchemaExtractionMain's own CLI
     * guard, fixed alongside this).
     */
    @Test
    fun `serialkompatExtract does not crash on a root project with discovery but no scan dirs`() {
        val projectDir = Files.createTempDirectory("skompat-bug4-e2e").toFile()
        try {
            File(projectDir, "settings.gradle.kts").writeText(
                """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                rootProject.name = "sample"
                """.trimIndent(),
            )
            File(projectDir, "build.gradle.kts").writeText(
                """
                plugins { id("com.chrisjenx.serialkompat") }
                serialkompat {
                    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
                }
                """.trimIndent(),
            )
            val result =
                GradleRunner
                    .create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withArguments("serialkompatExtract", "--stacktrace")
                    .build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatExtract")?.outcome)
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
