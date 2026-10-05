package com.chrisjenx.serialkompat.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build-wide lock around baseline extraction. Registered with `maxParallelUsages = 1` and used
 * by the pairwise check tasks, so under `--parallel` the modules of one build take turns: no
 * concurrent `git worktree add`/`prune` against the same repository, and no N nested Gradle
 * builds (each with the consumer's full daemon heap) started at once. Isolated-Projects-safe —
 * build services are the sanctioned way to share state across projects.
 */
internal abstract class BaselineExtractionService : BuildService<BuildServiceParameters.None> {
    internal companion object {
        const val NAME: String = "serialkompatBaselineExtraction"
    }
}
