package com.chrisjenx.serialkompat.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The baseline is extracted by a nested Gradle build that runs the module's
 * `serialkompatExtract` inside the worktree. Gradle rejects a task path with an empty
 * segment (`::serialkompatExtract`), so the root project must not be prefixed with its
 * own `:` path, or every single-module build fails its baseline extraction.
 */
class NestedExtractTaskTest {
    @Test
    fun `the root project's task path has no empty segment`() {
        assertEquals(":serialkompatExtract", nestedExtractTask(":"))
    }

    @Test
    fun `a subproject's task path is qualified by its project path`() {
        assertEquals(":app:serialkompatExtract", nestedExtractTask(":app"))
        assertEquals(":shared:api:serialkompatExtract", nestedExtractTask(":shared:api"))
    }
}
