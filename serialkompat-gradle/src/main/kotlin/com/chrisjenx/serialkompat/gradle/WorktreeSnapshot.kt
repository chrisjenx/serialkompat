package com.chrisjenx.serialkompat.gradle

import org.gradle.api.GradleException
import java.io.File

/**
 * Where the nested baseline `serialkompatExtract` is told to write its snapshot inside
 * [worktreeDir] (via [SerialkompatPlugin.EXTRACT_OUTPUT_PROPERTY]). Named explicitly rather than
 * guessed as `<module>/build/...`, because the baseline ref's build may relocate its build
 * directory (`layout.buildDirectory = ...`) and the outer build cannot know where.
 */
internal fun worktreeSnapshotFile(worktreeDir: File): File = File(worktreeDir, ".serialkompat-baseline.snapshot")

/** The nested Gradle invocation (minus the launcher) that extracts [projectPath]'s baseline into [output]. */
internal fun nestedExtractArguments(
    projectPath: String,
    output: File,
): List<String> =
    listOf(
        "$projectPath:${SerialkompatPlugin.EXTRACT_TASK_NAME}",
        "-P${SerialkompatPlugin.EXTRACT_OUTPUT_PROPERTY}=${output.absolutePath}",
        "--quiet",
    )

/**
 * Reads the snapshot that a nested `serialkompatExtract` run should have written inside
 * [worktreeDir] for the project at [projectDir] (relative to [rootDir]).
 *
 * The explicit [worktreeSnapshotFile] is tried first. A baseline ref that pins an older
 * serialkompat plugin ignores the output property and writes to its default
 * `<module>/build/serialkompat/current.snapshot`, so that is the fallback. The fallback can't read
 * a stale file: the worktree is a fresh checkout, and `build/` is never committed, so anything there
 * was just written by the nested build.
 *
 * The nested extraction can exit `0` and still never write the file: when the
 * baseline ref's committed config leaves `discovery=EXPLICIT` with empty `types`
 * (e.g. first-time `OPT_IN`/`OPT_OUT` adoption against an older baseline),
 * `serialkompatExtract`'s own `onlyIf` skips it there entirely. Reading a missing
 * file with a bare `.readText()` would surface a raw `NoSuchFileException`; fail
 * closed instead with a clear, actionable message — and never silently treat the
 * absence as an empty snapshot, which would reintroduce the false-safe
 * "everything added" outcome the `failOnEmptyBaseline` guard exists to prevent.
 */
internal fun readWorktreeSnapshot(
    rootDir: File,
    projectDir: File,
    worktreeDir: File,
): String {
    val explicit = worktreeSnapshotFile(worktreeDir)
    val relative = projectDir.relativeTo(rootDir)
    val legacy = File(worktreeDir, "$relative/build/serialkompat/current.snapshot")
    val snapshotFile =
        listOf(explicit, legacy).firstOrNull(File::isFile)
            ?: throw GradleException(
                "serialkompat: the baseline ref has no serialkompat configuration to extract — " +
                    "'serialkompatExtract' produced no snapshot there (expected " +
                    "${explicit.absolutePath} or ${legacy.absolutePath}). Check that the baseline " +
                    "ref's committed discovery/types configuration declares at least one type to check.",
            )
    return snapshotFile.readText()
}
