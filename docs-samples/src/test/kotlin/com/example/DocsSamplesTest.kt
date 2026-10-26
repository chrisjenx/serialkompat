package com.example

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The docs embed these models (issue #110). Compiling keeps them valid Kotlin; this keeps them
 * valid kotlinx-serialization models under the configs the docs point `jsonInstance` /
 * `cborInstance` at.
 */
@OptIn(ExperimentalSerializationApi::class)
class DocsSamplesTest {
    private inline fun <reified T> assertJsonRoundTrip(value: T) {
        val serializer: KSerializer<T> = serializer()
        val json = WireJson.instance.encodeToString(serializer, value)
        assertEquals(value, WireJson.instance.decodeFromString(serializer, json), json)
    }

    private inline fun <reified T> assertCborRoundTrip(value: T) {
        val serializer: KSerializer<T> = serializer()
        val bytes = WireCbor.instance.encodeToByteArray(serializer, value)
        assertEquals(value, WireCbor.instance.decodeFromByteArray(serializer, bytes))
    }

    @Test
    fun `sample models round-trip under the documented configs`() {
        assertJsonRoundTrip(Order(memo = "leave at the door"))
        assertJsonRoundTrip(OrderEvent(id = "o-1"))
        assertJsonRoundTrip(DebugDump(raw = "{}"))
        assertJsonRoundTrip(CacheEntry(key = "k", value = "v"))
        assertCborRoundTrip(CacheEntry(key = "k", value = "v"))
    }

    @Test
    fun `Order still reads the old note key`() {
        // docs/got-a-break.md promises @JsonNames keeps the pre-rename payload decodable.
        assertEquals(Order(memo = "x"), WireJson.instance.decodeFromString<Order>("""{"note":"x"}"""))
    }
}
