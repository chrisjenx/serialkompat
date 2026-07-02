package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeverityTest {
    @Test
    fun `severity is ordered SAFE lt WARN lt BREAK`() {
        assertTrue(Severity.BREAK.isAtLeast(Severity.WARN))
        assertTrue(Severity.WARN.isAtLeast(Severity.SAFE))
        assertTrue(Severity.BREAK.isAtLeast(Severity.BREAK))
    }

    @Test
    fun `lower severities are not at least a higher floor`() {
        assertFalse(Severity.SAFE.isAtLeast(Severity.WARN))
        assertFalse(Severity.WARN.isAtLeast(Severity.BREAK))
    }
}
