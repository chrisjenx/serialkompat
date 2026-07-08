package com.chrisjenx.serialkompat.gradle

import org.gradle.api.GradleException
import java.io.File

/**
 * Reads the `current.snapshot` that a nested `serialkompatExtract` run should have
 * written inside [worktreeDir] for the project at [projectDir] (relative to
 * [rootDir]).
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
    val relative = projectDir.relativeTo(rootDir)
    val snapshotFile = File(worktreeDir, "$relative/build/serialkompat/current.snapshot")
    if (!snapshotFile.isFile) {
        throw GradleException(
            "serialkompat: the baseline ref has no serialkompat configuration to extract — " +
                "'serialkompatExtract' produced no snapshot there (expected " +
                "${snapshotFile.absolutePath}). Check that the baseline ref's committed " +
                "discovery/types configuration declares at least one type to check.",
        )
    }
    return snapshotFile.readText()
}
