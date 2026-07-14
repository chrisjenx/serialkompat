package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesCatalogTest {
    @Test
    fun `Rules_all lists every declared rule constant exactly once`() {
        // Java reflection over the object's static final String fields — no kotlin-reflect dep.
        // Excludes the List-typed `all` field itself by filtering on String type.
        val declared =
            Rules::class.java.declaredFields
                .filter { it.type == String::class.java }
                .map {
                    it.isAccessible = true
                    it.get(Rules) as String
                }.toSet()
        assertEquals(declared, Rules.all.toSet(), "Rules.all must contain every rule constant")
        assertEquals(Rules.all.size, Rules.all.toSet().size, "Rules.all must have no duplicates")
        assertTrue(Rules.all.isNotEmpty())
    }
}
