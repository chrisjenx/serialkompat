package io.github.chrisjenx.serialkompat.cli

import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.core.CompatibilityEngine
import io.github.chrisjenx.serialkompat.core.CompatibilityProfile
import io.github.chrisjenx.serialkompat.core.ConsoleReporter
import io.github.chrisjenx.serialkompat.core.Severity
import io.github.chrisjenx.serialkompat.core.Snapshot
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

    /**
     * Runs the CLI, writing output (including error messages) to [out], and returns
     * the process exit code. The tool never throws on bad input (design §10): a
     * missing/unreadable/malformed snapshot file, an unknown flag, or an invalid
     * `--direction` all yield a controlled [EXIT_USAGE] with an `error:` message,
     * never a stack trace.
     */
    public fun run(
        args: Array<String>,
        out: Appendable,
    ): Int {
        if ("--help" in args || "-h" in args) {
            out.appendLine(USAGE)
            return EXIT_OK
        }

        val unknown = args.filter { it.startsWith("--") }.firstOrNull { it.substringBefore("=") !in KNOWN_OPTIONS }
        if (unknown != null) {
            out.appendLine("error: unknown option '$unknown'")
            out.appendLine(USAGE)
            return EXIT_USAGE
        }

        val positional = args.filterNot { it.startsWith("--") }
        if (positional.firstOrNull() != "diff" || positional.size < 3) {
            out.appendLine(USAGE)
            return EXIT_USAGE
        }

        val directionArg = optionValue(args, "--direction")
        val direction =
            if (directionArg == null) {
                CompatibilityDirection.FULL
            } else {
                runCatching { CompatibilityDirection.valueOf(directionArg) }.getOrElse {
                    out.appendLine("error: invalid --direction '$directionArg' (expected FULL, BACKWARD, or FORWARD)")
                    return EXIT_USAGE
                }
            }
        val failOnBreaking = !args.contains("--no-fail")

        val baseline = readSnapshot(positional[1], out) ?: return EXIT_USAGE
        val current = readSnapshot(positional[2], out) ?: return EXIT_USAGE
        val report = CompatibilityEngine.check(baseline, current, CompatibilityProfile(direction = direction))

        out.appendLine(ConsoleReporter.render(report))
        return if (failOnBreaking && report.shouldFail(Severity.BREAK)) EXIT_BREAKING else EXIT_OK
    }

    /** Reads and parses a snapshot file, reporting any IO/parse failure as a controlled error. */
    private fun readSnapshot(
        path: String,
        out: Appendable,
    ): Snapshot? =
        runCatching { SnapshotFormat.parse(File(path).readText()) }
            .getOrElse {
                out.appendLine("error: cannot read snapshot '$path': ${it.message}")
                null
            }

    private fun optionValue(
        args: Array<String>,
        name: String,
    ): String? = args.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private val KNOWN_OPTIONS = setOf("--direction", "--no-fail", "--help", "-h")

    @JvmStatic
    public fun main(args: Array<String>) {
        exitProcess(run(args, System.out))
    }

    private const val EXIT_OK = 0
    private const val EXIT_BREAKING = 1
    private const val EXIT_USAGE = 2
}
