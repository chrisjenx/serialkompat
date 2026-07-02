package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The compatibility engine binds to the user's real `Json` configuration rather
 * than a hand-redeclared copy (design §6): these settings change the wire shape
 * or decode behavior, so reading them off `Json.configuration` is a correctness
 * requirement, not a convenience.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonConfigReaderTest {
    @Test
    fun `a default Json maps to the default SnapshotConfig`() {
        assertEquals(SnapshotConfig(), JsonConfigReader.read(Json))
    }

    @Test
    fun `ignoreUnknownKeys is read`() {
        val config = JsonConfigReader.read(Json { ignoreUnknownKeys = true })
        assertEquals(true, config.ignoreUnknownKeys)
    }

    @Test
    fun `encodeDefaults is read`() {
        assertEquals(true, JsonConfigReader.read(Json { encodeDefaults = true }).encodeDefaults)
    }

    @Test
    fun `explicitNulls is read`() {
        assertEquals(false, JsonConfigReader.read(Json { explicitNulls = false }).explicitNulls)
    }

    @Test
    fun `coerceInputValues is read`() {
        assertEquals(true, JsonConfigReader.read(Json { coerceInputValues = true }).coerceInputValues)
    }

    @Test
    fun `classDiscriminator is read`() {
        assertEquals("kind", JsonConfigReader.read(Json { classDiscriminator = "kind" }).classDiscriminator)
    }

    @Test
    fun `no naming strategy reads as none`() {
        assertEquals("none", JsonConfigReader.read(Json).namingStrategy)
    }

    @Test
    fun `snake case naming strategy is read`() {
        val config = JsonConfigReader.read(Json { namingStrategy = JsonNamingStrategy.SnakeCase })
        assertEquals(JsonNamingStrategy.SnakeCase.toString(), config.namingStrategy)
    }

    /** A user naming strategy that does not override `toString()` (inherits `Object`'s identity form). */
    private object CustomStrategy : JsonNamingStrategy {
        override fun serialNameForJson(
            descriptor: SerialDescriptor,
            elementIndex: Int,
            serialName: String,
        ): String = serialName
    }

    @Test
    fun `a custom naming strategy is recorded by a stable class identity, not a per-JVM object hash`() {
        // Object.toString() defaults to "<class>@<identityHashCode>", whose hash differs across the
        // baseline-worktree JVM and the current JVM → a phantom CONFIG_NAMING_STRATEGY BREAK every run
        // (and a non-byte-stable snapshot). It must be recorded by a stable identity instead.
        val recorded = JsonConfigReader.read(Json { namingStrategy = CustomStrategy }).namingStrategy
        assertEquals(CustomStrategy::class.qualifiedName, recorded)
    }

    @Test
    fun `classDiscriminatorMode is read`() {
        // NONE drops the discriminator from the wire — a wire-affecting setting (design §6)
        // that must be captured so its change is detectable.
        val config = JsonConfigReader.read(Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE })
        assertEquals("NONE", config.classDiscriminatorMode)
        assertEquals("POLYMORPHIC", JsonConfigReader.read(Json).classDiscriminatorMode)
    }

    @Test
    fun `useAlternativeNames is read`() {
        assertEquals(false, JsonConfigReader.read(Json { useAlternativeNames = false }).useAlternativeNames)
        assertEquals(true, JsonConfigReader.read(Json).useAlternativeNames)
    }
}
