package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Config is part of the contract, so config *changes* are classified too
 * (design §6). These verify the severity assigned to each `Json` setting delta.
 */
class ClassifierConfigTest {
    private fun classify(change: Change): List<Finding> = Classifier().classify(listOf(change))

    private fun List<Finding>.backward(): Severity? =
        singleOrNull { it.direction == CompatibilityDirection.BACKWARD }?.severity

    @Test
    fun `flipping the naming strategy renames every key — BREAK both ways`() {
        val f = classify(Change.ConfigChanged("namingStrategy", "none", "SnakeCase"))
        assertEquals(Severity.BREAK, f.backward())
        assertEquals(Severity.BREAK, f.single { it.direction == CompatibilityDirection.FORWARD }.severity)
    }

    @Test
    fun `changing the class discriminator is a polymorphic BREAK`() {
        val f = classify(Change.ConfigChanged("classDiscriminator", "type", "kind"))
        assertEquals(Severity.BREAK, f.backward())
    }

    @Test
    fun `tightening ignoreUnknownKeys (true to false) is a WARN`() {
        val f = classify(Change.ConfigChanged("ignoreUnknownKeys", "true", "false"))
        assertEquals(Severity.WARN, f.backward())
    }

    @Test
    fun `loosening ignoreUnknownKeys (false to true) is safe`() {
        assertTrue(classify(Change.ConfigChanged("ignoreUnknownKeys", "false", "true")).isEmpty())
    }

    @Test
    fun `no longer encoding defaults (true to false) is a WARN`() {
        val f = classify(Change.ConfigChanged("encodeDefaults", "true", "false"))
        assertEquals(Severity.WARN, f.backward())
    }

    @Test
    fun `starting to encode defaults (false to true) is safe`() {
        assertTrue(classify(Change.ConfigChanged("encodeDefaults", "false", "true")).isEmpty())
    }

    @Test
    fun `changing explicitNulls is a WARN`() {
        val f = classify(Change.ConfigChanged("explicitNulls", "true", "false"))
        assertEquals(Severity.WARN, f.backward())
    }

    @Test
    fun `disabling coerceInputValues (true to false) is a WARN`() {
        val f = classify(Change.ConfigChanged("coerceInputValues", "true", "false"))
        assertEquals(Severity.WARN, f.backward())
    }

    @Test
    fun `enabling coerceInputValues (false to true) is safe`() {
        assertTrue(classify(Change.ConfigChanged("coerceInputValues", "false", "true")).isEmpty())
    }

    @Test
    fun `config findings carry the field as detail and a rule name`() {
        val finding = classify(Change.ConfigChanged("namingStrategy", "none", "SnakeCase")).first()
        assertTrue(finding.detail.contains("namingStrategy"))
        assertTrue(finding.rule.isNotBlank())
    }
}
