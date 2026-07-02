package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.AcceptedBreak
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.gradle.git.GitRefBaseline
import com.chrisjenx.serialkompat.gradle.git.SnapshotCache
import com.chrisjenx.serialkompat.gradle.git.SystemGit
import com.chrisjenx.serialkompat.gradle.history.PublishedHistory
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import java.time.Instant

/**
 * Registers the serialkompat compatibility-gate tasks (design §9):
 *
 * - **`serialkompatExtract`** runs the extractor on the project's own runtime
 *   classpath (via [JavaExec], so it sees the real compiled `@Serializable`
 *   types) and writes the current schema snapshot.
 * - **`serialkompatCheck`** recomputes the baseline from the target git ref
 *   (git-ref-live, [GitRefBaseline]) and compares it to the current schema,
 *   failing on an unacknowledged breaking change. Wired into `check`.
 */
public class SerialkompatPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("lifecycle-base")
        val extension = target.extensions.create("serialkompat", SerialkompatExtension::class.java)
        extension.direction.convention(CompatibilityDirection.FULL)
        extension.failOnBreaking.convention(true)
        extension.failOnEmptyBaseline.convention(true)
        // No baselineRef convention: an unset ref auto-detects the default branch at execution
        // time (issue #116), so a repo whose default is `master` works out of the box.
        extension.include.convention(listOf(""))
        extension.exclude.convention(emptyList())
        extension.acceptedBreaks.convention(emptyList())
        extension.renames.convention(emptyMap())
        // A source-controlled (not build/) location: the transitive check reads it, so it must persist.
        extension.history.dir.convention(target.layout.projectDirectory.dir("serialkompat/history"))

        val currentSnapshot = target.layout.buildDirectory.file("serialkompat/current.snapshot")

        val extract =
            target.tasks.register(EXTRACT_TASK_NAME, JavaExec::class.java) { task ->
                task.group = VERIFICATION_GROUP
                task.description = "Extracts the current @Serializable JSON wire schema to a snapshot file."
                task.mainClass.set("com.chrisjenx.serialkompat.extractor.SchemaExtractionMain")
                task.classpath(toolClasspath(target), projectRuntimeClasspath(target))
                task.outputs.file(currentSnapshot)
                task.onlyIf { extension.types.get().isNotEmpty() }
                task.argumentProviders.add {
                    buildList {
                        add("--types")
                        add(extension.types.get().joinToString(","))
                        add("--out")
                        add(currentSnapshot.get().asFile.absolutePath)
                        if (extension.jsonInstance.isPresent) {
                            add("--json")
                            add(extension.jsonInstance.get())
                        }
                    }
                }
            }

        // Capture everything the check needs at configuration time so its action never touches
        // `Task.project` at execution time — a hard error under the configuration cache (design §9).
        val rootDir = target.rootDir
        val projectDir = target.projectDir
        val projectPath = target.path
        val baselineDir =
            target.layout.buildDirectory
                .dir("serialkompat/baseline")
                .get()
                .asFile
        val worktreesDir =
            target.layout.buildDirectory
                .dir("serialkompat/worktrees")
                .get()
                .asFile
        val reportFile =
            target.layout.buildDirectory
                .file("serialkompat/report.json")
                .get()
                .asFile
        val currentFile = currentSnapshot.get().asFile
        // A Provider (not project.findProperty at execution) keeps -Pserialkompat.ref config-cache-safe.
        val refProperty = target.providers.gradleProperty(REF_PROPERTY)
        val recordVersionProperty = target.providers.gradleProperty(RECORD_VERSION_PROPERTY)
        // Captured at configuration time (a String, not the Project) so the record action never
        // touches `Task.project` at execution — config-cache-safe like the rest of the plugin.
        val projectVersion = target.version.toString()

        val check =
            target.tasks.register(CHECK_TASK_NAME) { task ->
                task.group = VERIFICATION_GROUP
                task.description = "Fails on backward/forward-incompatible JSON wire changes vs the baseline ref."
                task.dependsOn(extract)
                // Applying the plugin without declaring what crosses the wire is a no-op,
                // so `check` never breaks on an unconfigured project.
                task.onlyIf { extension.types.get().isNotEmpty() }
                task.doLast { t ->
                    runCheck(
                        logger = t.logger,
                        rootDir = rootDir,
                        projectDir = projectDir,
                        projectPath = projectPath,
                        current = currentFile,
                        baselineDir = baselineDir,
                        worktreesDir = worktreesDir,
                        reportFile = reportFile,
                        baselineRef = extension.baselineRef.orNull,
                        direction = extension.direction.get(),
                        include = extension.include.get(),
                        exclude = extension.exclude.get(),
                        failOnBreaking = extension.failOnBreaking.get(),
                        failOnEmptyBaseline = extension.failOnEmptyBaseline.get(),
                        accepted = extension.acceptedBreaks.get().map { parseAcceptedBreak(it) },
                        renames = extension.renames.get(),
                    )
                }
            }

        // Ad-hoc variant: check against any ref via -Pserialkompat.ref=<ref>, no config edit.
        target.tasks.register(CHECK_AGAINST_TASK_NAME) { task ->
            task.group = VERIFICATION_GROUP
            task.description = "Checks against an ad-hoc ref (-Pserialkompat.ref=<ref>), else the configured baseline."
            task.dependsOn(extract)
            task.onlyIf { extension.types.get().isNotEmpty() }
            task.doLast { t ->
                runCheck(
                    logger = t.logger,
                    rootDir = rootDir,
                    projectDir = projectDir,
                    projectPath = projectPath,
                    current = currentFile,
                    baselineDir = baselineDir,
                    worktreesDir = worktreesDir,
                    reportFile = reportFile,
                    baselineRef = resolveBaselineRef(refProperty.orNull, extension.baselineRef.orNull),
                    direction = extension.direction.get(),
                    include = extension.include.get(),
                    exclude = extension.exclude.get(),
                    failOnBreaking = extension.failOnBreaking.get(),
                    failOnEmptyBaseline = extension.failOnEmptyBaseline.get(),
                    accepted = extension.acceptedBreaks.get().map { parseAcceptedBreak(it) },
                    renames = extension.renames.get(),
                )
            }
        }

        // Records the current schema into the append-only published history (design §5), keyed by
        // version. Run from the release flow (or manually) and commit the result; the transitive
        // check reads it. Not wired into `check` — recording is an explicit, release-time act.
        target.tasks.register(RECORD_TASK_NAME) { task ->
            task.group = VERIFICATION_GROUP
            task.description = "Records the current schema into the published history (-Pserialkompat.recordVersion=X)."
            task.dependsOn(extract)
            task.onlyIf { extension.types.get().isNotEmpty() }
            val historyDir =
                extension.history.dir
                    .get()
                    .asFile
            val version = resolveRecordVersion(recordVersionProperty.orNull, projectVersion)
            task.doLast { t ->
                runRecord(t.logger, currentFile, historyDir, version)
            }
        }

        // Transitive persisted-data check: the current schema vs EVERY published version at once,
        // so a break against a version older than the latest still fails (design §5). A no-op when
        // no history has been recorded yet.
        val checkHistory =
            target.tasks.register(CHECK_HISTORY_TASK_NAME) { task ->
                task.group = VERIFICATION_GROUP
                task.description = "Fails on changes incompatible with any published schema version (transitive)."
                task.dependsOn(extract)
                val historyDir =
                    extension.history.dir
                        .get()
                        .asFile
                task.onlyIf { extension.types.get().isNotEmpty() && hasHistory(historyDir) }
                task.doLast { t ->
                    runCheckHistory(
                        logger = t.logger,
                        current = currentFile,
                        historyDir = historyDir,
                        reportFile = reportFile,
                        direction = extension.direction.get(),
                        include = extension.include.get(),
                        exclude = extension.exclude.get(),
                        failOnBreaking = extension.failOnBreaking.get(),
                        accepted = extension.acceptedBreaks.get().map { parseAcceptedBreak(it) },
                    )
                }
            }

        // Only gate `check` once the project has declared what crosses the wire. The transitive
        // history check joins the gate too, but its own `onlyIf` makes it a no-op until a history
        // has been recorded — so it never breaks a project that hasn't opted in.
        target.tasks.named("check").configure {
            it.dependsOn(check)
            it.dependsOn(checkHistory)
        }
    }

    @Suppress("LongParameterList")
    private fun runCheck(
        logger: Logger,
        rootDir: File,
        projectDir: File,
        projectPath: String,
        current: File,
        baselineDir: File,
        worktreesDir: File,
        reportFile: File,
        baselineRef: String?,
        direction: CompatibilityDirection,
        include: List<String>,
        exclude: List<String>,
        failOnBreaking: Boolean,
        failOnEmptyBaseline: Boolean,
        accepted: List<AcceptedBreak>,
        renames: Map<String, String>,
    ) {
        val gitCli = SystemGit(rootDir)
        val git = GitRefBaseline(gitCli)
        val cache = SnapshotCache(baselineDir)
        worktreesDir.mkdirs()

        // An unconfigured, un-overridden ref auto-detects the default branch (issue #116).
        val effectiveRef = baselineRef ?: resolveDefaultBranch(gitCli)

        val baselineText =
            git.snapshotAt(effectiveRef, worktreesDir, cache) { worktreeDir ->
                extractInWorktree(rootDir, projectDir, projectPath, worktreeDir)
            }

        val outcome =
            CheckExecutor.execute(
                baselineText = baselineText,
                currentText = current.readText(),
                direction = direction,
                include = include,
                exclude = exclude,
                failOnBreaking = failOnBreaking,
                failOnEmptyBaseline = failOnEmptyBaseline,
                accepted = accepted,
                renames = renames,
            )
        logger.lifecycle(outcome.console)
        reportFile.also { it.parentFile.mkdirs() }.writeText(outcome.json)
        if (outcome.failed) {
            throw GradleException("serialkompat: incompatible wire changes vs '$effectiveRef'. See the report above.")
        }
    }

    private fun runRecord(
        logger: Logger,
        current: File,
        historyDir: File,
        version: String,
    ) {
        val snapshot = SnapshotFormat.parse(current.readText())
        val history = PublishedHistory(historyDir)
        history.record(version, snapshot, Instant.now())
        logger.lifecycle("serialkompat: recorded schema for version '$version' into ${historyDir.absolutePath}")
    }

    @Suppress("LongParameterList")
    private fun runCheckHistory(
        logger: Logger,
        current: File,
        historyDir: File,
        reportFile: File,
        direction: CompatibilityDirection,
        include: List<String>,
        exclude: List<String>,
        failOnBreaking: Boolean,
        accepted: List<AcceptedBreak>,
    ) {
        val history = PublishedHistory(historyDir).snapshots()
        val outcome =
            CheckExecutor.executeHistory(
                currentText = current.readText(),
                history = history,
                direction = direction,
                include = include,
                exclude = exclude,
                failOnBreaking = failOnBreaking,
                accepted = accepted,
            )
        logger.lifecycle(outcome.console)
        reportFile.also { it.parentFile.mkdirs() }.writeText(outcome.json)
        if (outcome.failed) {
            throw GradleException("serialkompat: schema incompatible with published history. See the report above.")
        }
    }

    /**
     * Extracts the baseline schema by running `serialkompatExtract` in the target
     * ref's worktree via a nested Gradle build — the git-ref-live recompute.
     */
    private fun extractInWorktree(
        rootDir: File,
        projectDir: File,
        projectPath: String,
        worktreeDir: File,
    ): String {
        val gradlew = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
        val launcher = File(rootDir, gradlew).takeIf(File::exists)?.absolutePath ?: "gradle"
        val process =
            ProcessBuilder(launcher, "$projectPath:$EXTRACT_TASK_NAME", "--quiet")
                .directory(worktreeDir)
                .redirectErrorStream(true)
                .start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "serialkompat: baseline extraction failed in worktree:\n$log" }
        val relative = projectDir.relativeTo(rootDir)
        return File(worktreeDir, "$relative/build/serialkompat/current.snapshot").readText()
    }

    private fun toolClasspath(project: Project): FileCollection {
        val markers =
            listOf(
                "com.chrisjenx.serialkompat.extractor.SchemaExtractionMain",
                "com.chrisjenx.serialkompat.core.Snapshot",
                "kotlinx.serialization.json.Json",
                "kotlinx.serialization.KSerializer",
                "kotlin.reflect.full.KClasses",
                "kotlin.reflect.jvm.internal.KClassImpl",
                "kotlin.Unit",
            )
        val jars =
            markers.mapNotNull { name ->
                runCatching {
                    Class
                        .forName(name)
                        .protectionDomain
                        ?.codeSource
                        ?.location
                        ?.toURI()
                        ?.let(::File)
                }.getOrNull()
            }
        return project.files(jars)
    }

    private fun projectRuntimeClasspath(project: Project): FileCollection {
        val runtime = project.configurations.findByName("runtimeClasspath")
        val mainOutput =
            project.extensions
                .findByType(SourceSetContainer::class.java)
                ?.findByName("main")
                ?.output
        return project.files(listOfNotNull(runtime, mainOutput))
    }

    public companion object {
        public const val EXTRACT_TASK_NAME: String = "serialkompatExtract"
        public const val CHECK_TASK_NAME: String = "serialkompatCheck"
        public const val CHECK_AGAINST_TASK_NAME: String = "serialkompatCheckAgainst"
        public const val RECORD_TASK_NAME: String = "serialkompatRecord"
        public const val CHECK_HISTORY_TASK_NAME: String = "serialkompatCheckHistory"
        private const val REF_PROPERTY: String = "serialkompat.ref"
        private const val RECORD_VERSION_PROPERTY: String = "serialkompat.recordVersion"
        private const val VERIFICATION_GROUP: String = "verification"

        /** True if [historyDir] holds at least one recorded `.snapshot` entry. */
        private fun hasHistory(historyDir: File): Boolean =
            historyDir.listFiles { f -> f.isFile && f.name.endsWith(".snapshot") }?.isNotEmpty() == true

        /**
         * The version to record under: an explicit `-Pserialkompat.recordVersion` wins, else the
         * project version. Fails closed on an unusable version rather than writing a junk entry into
         * the append-only, never-overwritten history.
         */
        private fun resolveRecordVersion(
            property: String?,
            projectVersion: String,
        ): String {
            val version = property?.trim()?.takeIf(String::isNotEmpty) ?: projectVersion.trim()
            require(version.isNotEmpty() && version != "unspecified") {
                "serialkompat: cannot record history without a version — set the project `version` or pass " +
                    "-Pserialkompat.recordVersion=<X.Y.Z>."
            }
            // The version is both the file stem and a space-delimited field in the @history header;
            // whitespace would corrupt both, so reject it rather than write an unloadable entry.
            require(version.none(Char::isWhitespace)) {
                "serialkompat: record version '$version' must not contain whitespace."
            }
            return version
        }
    }
}
