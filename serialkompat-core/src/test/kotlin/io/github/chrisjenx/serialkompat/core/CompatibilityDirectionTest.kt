package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityDirectionTest {
    @Test
    fun `FULL checks both directions`() {
        assertTrue(CompatibilityDirection.FULL.checksBackward)
        assertTrue(CompatibilityDirection.FULL.checksForward)
    }

    @Test
    fun `BACKWARD checks only backward`() {
        assertTrue(CompatibilityDirection.BACKWARD.checksBackward)
        assertFalse(CompatibilityDirection.BACKWARD.checksForward)
    }

    @Test
    fun `FORWARD checks only forward`() {
        assertTrue(CompatibilityDirection.FORWARD.checksForward)
        assertFalse(CompatibilityDirection.FORWARD.checksBackward)
    }
}
