package io.github.chrisjenx.serialkompat.cli

import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.core.CompatibilityEngine
import io.github.chrisjenx.serialkompat.core.CompatibilityProfile
import io.github.chrisjenx.serialkompat.core.ConsoleReporter
import io.github.chrisjenx.serialkompat.core.Severity
import io.github.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone CLI for non-Gradle / cross-repo use (design §13): it diffs two
 * snapshot files produced by `serialkompatExtract` in different repos or refs and
 * applies the same pure engine and report. Extraction stays in Gradle (it needs a
 * classpath); the CLI is the portable comparison front end.
 */
public object SerialkompatCli {
    private const val USAGE =
        "usage: serialkompat diff <baseline.snapshot> <current.snapshot> " +
            "[--direction=FULL|BACKWARD|FORWARD] [--no-fail]"

    /** Runs the CLI, writing output to [out], and returns the process exit code. */
    public fun run(
        args: Array<String>,
        out: Appendable,
    ): Int {
        val positional = args.filterNot { it.startsWith("--") }
        if (positional.firstOrNull() != "diff" || positional.size < 3) {
            out.appendLine(USAGE)
            return EXIT_USAGE
        }
        val direction =
            optionValue(args, "--direction")
                ?.let { runCatching { CompatibilityDirection.valueOf(it) }.getOrNull() }
                ?: CompatibilityDirection.FULL
        val failOnBreaking = !args.contains("--no-fail")

        val baseline = SnapshotFormat.parse(File(positional[1]).readText())
        val current = SnapshotFormat.parse(File(positional[2]).readText())
        val report = CompatibilityEngine.check(baseline, current, CompatibilityProfile(direction = direction))

        out.appendLine(ConsoleReporter.render(report))
        return if (failOnBreaking && report.shouldFail(Severity.BREAK)) EXIT_BREAKING else EXIT_OK
    }

    private fun optionValue(
        args: Array<String>,
        name: String,
    ): String? = args.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    @JvmStatic
    public fun main(args: Array<String>) {
        exitProcess(run(args, System.out))
    }

    private const val EXIT_OK = 0
    private const val EXIT_BREAKING = 1
    private const val EXIT_USAGE = 2
}
