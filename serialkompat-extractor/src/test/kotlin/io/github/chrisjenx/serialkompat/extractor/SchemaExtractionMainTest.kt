package io.github.chrisjenx.serialkompat.extractor

import io.github.chrisjenx.serialkompat.core.SnapshotFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extraction entry point runs on the *target project's* classpath (via the
 * Gradle task's JavaExec), loads the configured `@Serializable` types by name,
 * and writes their snapshot. Here it is exercised directly — the test types are
 * on this JVM's classpath, so `Class.forName` resolves them without a subprocess.
 */
class SchemaExtractionMainTest {
    @Serializable
    @SerialName("Order")
    data class Order(
        val id: String,
        val note: String = "",
    )

    @Serializable
    @SerialName("Status")
    enum class Status { NEW, DONE }

    object WireJson {
        val instance: Json = Json { ignoreUnknownKeys = true }
    }

    private val tempDir: File = Files.createTempDirectory("skompat-extract").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `extracts named types into a snapshot file`() {
        val out = File(tempDir, "current.snapshot")
        SchemaExtractionMain.run(
            typeNames = listOf(Order::class.java.name, Status::class.java.name),
            jsonInstanceFqn = null,
            output = out,
        )
        val snapshot = SnapshotFormat.parse(out.readText())
        assertEquals(
            listOf("id", "note"),
            snapshot.contracts
                .single { it.serialName == "Order" }
                .elements
                .map { it.name },
        )
        assertTrue(snapshot.contracts.any { it.serialName == "Status" })
    }

    @Test
    fun `reads config from a named Json instance`() {
        val out = File(tempDir, "current.snapshot")
        SchemaExtractionMain.run(
            typeNames = listOf(Order::class.java.name),
            jsonInstanceFqn = "${WireJson::class.java.name}.instance",
            output = out,
        )
        assertEquals(true, SnapshotFormat.parse(out.readText()).config.ignoreUnknownKeys)
    }

    @Test
    fun `falls back to default config when the Json instance can't be loaded`() {
        val out = File(tempDir, "current.snapshot")
        SchemaExtractionMain.run(
            typeNames = listOf(Order::class.java.name),
            jsonInstanceFqn = "com.example.DoesNotExist.instance",
            output = out,
        )
        // Design §6: fall back to conservative defaults rather than crashing.
        assertEquals(false, SnapshotFormat.parse(out.readText()).config.ignoreUnknownKeys)
    }

    @Test
    fun `creates parent directories for the output file`() {
        val out = File(tempDir, "nested/dir/current.snapshot")
        SchemaExtractionMain.run(listOf(Order::class.java.name), null, out)
        assertTrue(out.isFile)
    }
}
