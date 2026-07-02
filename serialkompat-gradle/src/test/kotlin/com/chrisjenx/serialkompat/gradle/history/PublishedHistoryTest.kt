package com.chrisjenx.serialkompat.gradle.history

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublishedHistoryTest {
    private val dir: File = Files.createTempDirectory("skompat-history").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun order(field: String) =
        Snapshot(
            listOf(
                Contract("com.example.Order", ContractKind.CLASS, elements = listOf(Element(field, "kotlin.String"))),
            ),
        )

    @Test
    fun `a fresh history is empty`() {
        assertTrue(PublishedHistory(dir).load().isEmpty())
    }

    @Test
    fun `record then load round-trips`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"))
        assertEquals(listOf(order("id")), history.load())
    }

    @Test
    fun `history is append-only — recording an existing version fails`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"))
        assertFailsWith<IllegalArgumentException> { history.record("1.0.0", order("changed")) }
    }

    @Test
    fun `load returns versions in file-name order`() {
        val history = PublishedHistory(dir)
        history.record("1.1.0", order("b"))
        history.record("1.0.0", order("a"))
        assertEquals(listOf(order("a"), order("b")), history.load())
    }
}
