package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.core.WireFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extracting a CBOR snapshot: the `Cbor` instance's config and module, and no JSON-only facts. */
@OptIn(ExperimentalSerializationApi::class)
class CborExtractionTest {
    @Serializable
    @SerialName("Order")
    data class Order(
        @JsonNames("orderId") val id: String,
        val note: String = "",
    )

    @Serializable
    sealed interface Event

    @Serializable
    @SerialName("created")
    data class Created(
        val at: Long,
    ) : Event

    interface Payload

    @Serializable
    @SerialName("text")
    data class Text(
        val body: String,
    ) : Payload

    object WireCbor {
        val strict: Cbor = Cbor

        val lenient: Cbor =
            Cbor {
                ignoreUnknownKeys = true
                encodeDefaults = true
                alwaysUseByteString = true
                serializersModule = SerializersModule { polymorphic(Payload::class) { subclass(Text::class) } }
            }

        val notCbor: String = "nope"
    }

    private val tempDir: File = Files.createTempDirectory("skompat-cbor").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun run(
        types: List<String>,
        cborInstance: String?,
    ): File {
        val out = File(tempDir, "current.snapshot")
        SchemaExtractionMain.run(
            typeNames = types,
            jsonInstanceFqn = null,
            output = out,
            format = WireFormat.CBOR,
            cborInstanceFqn = cborInstance,
        )
        return out
    }

    @Test
    fun `reads every Cbor setting into a CBOR config`() {
        val config =
            CborConfigReader.read(
                Cbor {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    alwaysUseByteString = true
                    preferCborLabelsOverNames = true
                    useDefiniteLengthEncoding = true
                    encodeKeyTags = true
                    encodeValueTags = true
                    encodeObjectTags = true
                    verifyKeyTags = true
                    verifyValueTags = true
                    verifyObjectTags = true
                },
            )
        assertEquals(
            SnapshotConfig.cbor(
                ignoreUnknownKeys = true,
                encodeDefaults = true,
                alwaysUseByteString = true,
                preferCborLabelsOverNames = true,
                useDefiniteLengthEncoding = true,
                encodeKeyTags = true,
                encodeValueTags = true,
                encodeObjectTags = true,
                verifyKeyTags = true,
                verifyValueTags = true,
                verifyObjectTags = true,
            ),
            config,
        )
    }

    @Test
    fun `the default Cbor reads as the default CBOR config`() {
        assertEquals(SnapshotConfig.cbor(), CborConfigReader.read(Cbor))
    }

    @Test
    fun `an unset cbor instance writes a default CBOR snapshot`() {
        val snapshot = SnapshotFormat.parse(run(listOf(Order::class.java.name), null).readText())
        assertEquals(SnapshotConfig.cbor(), snapshot.config)
    }

    @Test
    fun `a named Cbor instance supplies the config`() {
        val snapshot =
            SnapshotFormat.parse(run(listOf(Order::class.java.name), "${WireCbor::class.java.name}.lenient").readText())
        assertEquals(
            SnapshotConfig.cbor(ignoreUnknownKeys = true, encodeDefaults = true, alwaysUseByteString = true),
            snapshot.config,
        )
    }

    @Test
    fun `a named Cbor instance supplies the module's open-polymorphic subtypes`() {
        val snapshot =
            SnapshotFormat.parse(
                run(listOf(PayloadHolder::class.java.name), "${WireCbor::class.java.name}.lenient").readText(),
            )
        assertEquals(
            listOf("text"),
            snapshot.contracts
                .single { it.serialName.endsWith("Payload") }
                .subtypes
                .map { it.serialName },
        )
    }

    @Serializable
    @SerialName("PayloadHolder")
    data class PayloadHolder(
        @kotlinx.serialization.Polymorphic val payload: Payload,
    )

    @Test
    fun `fails loudly when the configured Cbor instance can't be loaded`() {
        val failure =
            assertFailsWith<IllegalStateException> { run(listOf(Order::class.java.name), "com.example.Missing.cbor") }
        assertTrue("com.example.Missing.cbor" in failure.message.orEmpty(), failure.message)
        assertTrue("cborInstance" in failure.message.orEmpty(), failure.message)
        assertFalse(File(tempDir, "current.snapshot").exists())
    }

    @Test
    fun `fails loudly when the named member is not a Cbor`() {
        assertFailsWith<IllegalStateException> {
            run(listOf(Order::class.java.name), "${WireCbor::class.java.name}.notCbor")
        }
    }

    @Test
    fun `a CBOR snapshot records no JsonNames aliases`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(listOf(serializer<Order>().descriptor), config = SnapshotConfig.cbor())
        assertTrue(snapshot.contracts.single().elements.all { it.jsonNames.isEmpty() })
    }

    @Test
    fun `a CBOR snapshot records no class discriminator`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(listOf(serializer<Event>().descriptor), config = SnapshotConfig.cbor())
        assertNull(snapshot.contracts.single { it.subtypes.isNotEmpty() }.discriminator)
    }

    @Test
    fun `the strict haynet-style default Cbor is checked as strict`() {
        val snapshot =
            SnapshotFormat.parse(run(listOf(Order::class.java.name), "${WireCbor::class.java.name}.strict").readText())
        assertFalse(snapshot.config.ignoreUnknownKeys)
        assertEquals(WireFormat.CBOR, snapshot.config.format)
    }
}
