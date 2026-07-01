package io.github.chrisjenx.serialkompat.extractor

import io.github.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
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
}
