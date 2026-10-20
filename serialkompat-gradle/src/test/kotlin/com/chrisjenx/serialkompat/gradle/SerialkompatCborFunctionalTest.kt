package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.core.WireFormat
import com.chrisjenx.serialkompat.gradle.git.SnapshotCache
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CBOR end to end (design §6a): `format = CBOR` plus a project `Cbor` instance, through the real
 * forked extractor and `serialkompatCheck`. The case it exists for: a strict `Cbor` cache reader in
 * an old build crashes on a field a newer build wrote.
 */
class SerialkompatCborFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-cbor").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun write(
        path: String,
        content: String,
    ) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
    }

    private fun project(
        serialkompatBlock: String,
        orderBody: String = "val id: String",
    ) {
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            rootProject.name = "sample"
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                kotlin("plugin.serialization") version "2.3.21"
                id("com.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0") }
            serialkompat {
                types.set(listOf("com.example.Order"))
                $serialkompatBlock
            }
            """,
        )
        write(
            "src/main/kotlin/com/example/Order.kt",
            """
            package com.example

            import kotlinx.serialization.ExperimentalSerializationApi
            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.cbor.Cbor

            @Serializable
            @SerialName("com.example.Order")
            data class Order($orderBody)

            @OptIn(ExperimentalSerializationApi::class)
            object Wire {
                val strict: Cbor = Cbor
                val lenient: Cbor = Cbor { ignoreUnknownKeys = true }
            }
            """,
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    private fun git(vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
    }

    /**
     * Commits the tree as the baseline and seeds the baseline cache with its real extraction, so
     * the check needs no nested worktree build.
     */
    private fun commitBaseline() {
        runner("serialkompatExtract").build()
        val baseline = File(projectDir, "build/serialkompat/current.snapshot").readText()
        git("init", "--quiet")
        git("add", "-A", ":!build", ":!.gradle")
        git("-c", "user.email=t@t.io", "-c", "user.name=t", "commit", "--quiet", "-m", "baseline")
        val sha =
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(projectDir)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        SnapshotCache(File(projectDir, "build/serialkompat/baseline"), toolVersion = null).put(sha, baseline)
    }

    @Test
    fun `format CBOR extracts a CBOR snapshot from the named Cbor instance`() {
        project(
            """
            format.set(com.chrisjenx.serialkompat.core.WireFormat.CBOR)
            cborInstance.set("com.example.Wire.lenient")
            """,
        )

        val result = runner("serialkompatExtract").build()

        val text = File(projectDir, "build/serialkompat/current.snapshot").readText()
        assertTrue(text.contains("format=cbor"), "snapshot was:\n$text\n${result.output}")
        val snapshot = SnapshotFormat.parse(text)
        assertEquals(WireFormat.CBOR, snapshot.config.format)
        assertEquals(SnapshotConfig.cbor(ignoreUnknownKeys = true), snapshot.config)
    }

    @Test
    fun `a field added under a strict Cbor reader fails the check forward`() {
        project(
            """
            format.set(com.chrisjenx.serialkompat.core.WireFormat.CBOR)
            cborInstance.set("com.example.Wire.strict")
            baselineRef.set("HEAD")
            """,
        )
        commitBaseline()
        project(
            """
            format.set(com.chrisjenx.serialkompat.core.WireFormat.CBOR)
            cborInstance.set("com.example.Wire.strict")
            baselineRef.set("HEAD")
            """,
            orderBody = "val id: String, val note: String = \"\"",
        )

        val result = runner("serialkompatCheck").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatCheck")?.outcome)
        val report = File(projectDir, "build/serialkompat/report.json").readText()
        assertTrue(report.contains("PROPERTY_ADDED") && report.contains("FORWARD"), report)
    }

    @Test
    fun `the same added field passes under an ignoreUnknownKeys Cbor reader`() {
        val block =
            """
            format.set(com.chrisjenx.serialkompat.core.WireFormat.CBOR)
            cborInstance.set("com.example.Wire.lenient")
            baselineRef.set("HEAD")
            """
        project(block)
        commitBaseline()
        project(block, orderBody = "val id: String, val note: String = \"\"")

        val result = runner("serialkompatCheck").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatCheck")?.outcome)
    }

    @Test
    fun `cborInstance without format CBOR fails loudly`() {
        project("""cborInstance.set("com.example.Wire.strict")""")

        val result = runner("serialkompatExtract").buildAndFail()

        assertTrue(result.output.contains("cborInstance is set but format is JSON"), result.output)
    }
}
