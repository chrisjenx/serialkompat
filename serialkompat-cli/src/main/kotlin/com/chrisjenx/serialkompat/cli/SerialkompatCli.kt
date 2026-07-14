package com.chrisjenx.serialkompat.cli

import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.CompatibilityEngine
import com.chrisjenx.serialkompat.core.CompatibilityProfile
import com.chrisjenx.serialkompat.core.ConsoleReporter
import com.chrisjenx.serialkompat.core.GithubReporter
import com.chrisjenx.serialkompat.core.JsonReporter
import com.chrisjenx.serialkompat.core.SarifReporter
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
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
            "[--direction=FULL|BACKWARD|FORWARD] [--format=console|json|sarif|github] [--no-fail]"

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

        // Options that take a space-form value (`--name VALUE`); their VALUE index must be
        // excluded from the positional args so it is not mistaken for a file argument.
        val consumedValueIndices =
            VALUE_OPTIONS
                .mapNotNull { opt ->
                    args.indexOf(opt).let { i ->
                        if (i >= 0 && i + 1 < args.size && !args[i + 1].startsWith("--")) i + 1 else null
                    }
                }.toSet()
        val positional = args.filterIndexed { i, a -> i !in consumedValueIndices && !a.startsWith("--") }
        if (positional.firstOrNull() != "diff" || positional.size < 3) {
            out.appendLine(USAGE)
            return EXIT_USAGE
        }

        // Distinguish "no --direction" (default FULL) from "--direction with no value" (an error) —
        // the latter must not silently fall back to FULL. Both `--direction=X` and `--direction X` work.
        val directionPresent = args.any { it == "--direction" || it.startsWith("--direction=") }
        val directionArg = optionValue(args, "--direction")
        val direction =
            when {
                !directionPresent -> CompatibilityDirection.FULL
                directionArg == null -> {
                    out.appendLine("error: --direction requires a value (FULL, BACKWARD, or FORWARD)")
                    return EXIT_USAGE
                }
                else ->
                    runCatching { CompatibilityDirection.valueOf(directionArg) }.getOrElse {
                        out.appendLine(
                            "error: invalid --direction '$directionArg' (expected FULL, BACKWARD, or FORWARD)",
                        )
                        return EXIT_USAGE
                    }
            }
        val formatPresent = args.any { it == "--format" || it.startsWith("--format=") }
        val formatArg = optionValue(args, "--format")
        val format =
            when {
                !formatPresent -> OutputFormat.CONSOLE
                formatArg == null -> {
                    out.appendLine("error: --format requires a value (console, json, sarif, or github)")
                    return EXIT_USAGE
                }
                else ->
                    runCatching { OutputFormat.valueOf(formatArg.uppercase()) }.getOrElse {
                        out.appendLine(
                            "error: invalid --format '$formatArg' (expected console, json, sarif, or github)",
                        )
                        return EXIT_USAGE
                    }
            }

        val failOnBreaking = !args.contains("--no-fail")

        val baseline = readSnapshot(positional[1], out) ?: return EXIT_USAGE
        val current = readSnapshot(positional[2], out) ?: return EXIT_USAGE
        val report = CompatibilityEngine.check(baseline, current, CompatibilityProfile(direction = direction))

        val rendered =
            when (format) {
                OutputFormat.CONSOLE -> ConsoleReporter.render(report)
                OutputFormat.JSON -> JsonReporter.render(report)
                OutputFormat.SARIF -> SarifReporter.render(report, toolVersion())
                OutputFormat.GITHUB -> GithubReporter.render(report)
            }
        out.appendLine(rendered)
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

    /** Resolves an option's value in either `--name=value` or `--name value` form (null if absent/valueless). */
    private fun optionValue(
        args: Array<String>,
        name: String,
    ): String? {
        args.firstOrNull { it.startsWith("$name=") }?.let { return it.substringAfter("=") }
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size && !args[i + 1].startsWith("--")) args[i + 1] else null
    }

    private val KNOWN_OPTIONS = setOf("--direction", "--format", "--no-fail", "--help", "-h")

    /** Space-form value options — the CLI must consume their following token as a value, not a positional. */
    private val VALUE_OPTIONS = setOf("--direction", "--format")

    /** The output format selected by `--format` (default [CONSOLE]). */
    private enum class OutputFormat { CONSOLE, JSON, SARIF, GITHUB }

    /** Tool version from the jar manifest; null under dev/test runs (SARIF omits `version` when null). */
    private fun toolVersion(): String? = SerialkompatCli::class.java.getPackage()?.implementationVersion

    @JvmStatic
    public fun main(args: Array<String>) {
        exitProcess(run(args, System.out))
    }

    private const val EXIT_OK = 0
    private const val EXIT_BREAKING = 1
    private const val EXIT_USAGE = 2
}
