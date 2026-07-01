package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.core.Contract
import io.github.chrisjenx.serialkompat.core.ContractKind
import io.github.chrisjenx.serialkompat.core.Element
import io.github.chrisjenx.serialkompat.core.Snapshot
import io.github.chrisjenx.serialkompat.core.SnapshotFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure check orchestration behind `serialkompatCheck`: parse the baseline and
 * current snapshot texts, run the engine under the configured policy, render the
 * report, and decide whether to fail. Testable without Gradle.
 */
class CheckExecutorTest {
    private fun snapshot(vararg elements: Element) =
        SnapshotFormat.serialize(
            Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList()))),
        )

    private fun execute(
        baseline: String,
        current: String,
        failOnBreaking: Boolean = true,
    ) = CheckExecutor.execute(
        baselineText = baseline,
        currentText = current,
        direction = CompatibilityDirection.FULL,
        include = listOf(""),
        exclude = emptyList(),
        failOnBreaking = failOnBreaking,
    )

    @Test
    fun `identical schemas pass`() {
        val text = snapshot(Element("id", "kotlin.String"))
        val outcome = execute(text, text)
        assertFalse(outcome.failed)
    }

    @Test
    fun `removing a required field fails the check`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot())
        assertTrue(outcome.failed)
        assertTrue(outcome.console.contains("BREAK"))
    }

    @Test
    fun `a breaking change does not fail when failOnBreaking is false`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot(), failOnBreaking = false)
        assertFalse(outcome.failed)
        // The finding is still reported for visibility.
        assertTrue(outcome.console.contains("BREAK"))
    }

    @Test
    fun `the outcome carries a machine-readable json report`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot())
        assertTrue(outcome.json.contains("\"breaking\""))
    }
}
