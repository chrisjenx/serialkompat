package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.AcceptedBreak
import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.gradle.git.GitRefBaseline
import io.github.chrisjenx.serialkompat.gradle.git.SnapshotCache
import io.github.chrisjenx.serialkompat.gradle.git.SystemGit
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

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
        extension.baselineRef.convention("origin/main")
        extension.include.convention(listOf(""))
        extension.exclude.convention(emptyList())
        extension.acceptedBreaks.convention(emptyList())
        extension.renames.convention(emptyMap())

        val currentSnapshot = target.layout.buildDirectory.file("serialkompat/current.snapshot")

        val extract =
            target.tasks.register(EXTRACT_TASK_NAME, JavaExec::class.java) { task ->
                task.group = VERIFICATION_GROUP
                task.description = "Extracts the current @Serializable JSON wire schema to a snapshot file."
                task.mainClass.set("io.github.chrisjenx.serialkompat.extractor.SchemaExtractionMain")
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
                        baselineRef = extension.baselineRef.get(),
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
                    baselineRef = resolveBaselineRef(refProperty.orNull, extension.baselineRef.get()),
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

        // Only gate `check` once the project has declared what crosses the wire.
        target.tasks.named("check").configure { it.dependsOn(check) }
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
        baselineRef: String,
        direction: CompatibilityDirection,
        include: List<String>,
        exclude: List<String>,
        failOnBreaking: Boolean,
        failOnEmptyBaseline: Boolean,
        accepted: List<AcceptedBreak>,
        renames: Map<String, String>,
    ) {
        val git = GitRefBaseline(SystemGit(rootDir))
        val cache = SnapshotCache(baselineDir)
        worktreesDir.mkdirs()

        val baselineText =
            git.snapshotAt(baselineRef, worktreesDir, cache) { worktreeDir ->
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
            throw GradleException("serialkompat: incompatible wire changes vs '$baselineRef'. See the report above.")
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
                "io.github.chrisjenx.serialkompat.extractor.SchemaExtractionMain",
                "io.github.chrisjenx.serialkompat.core.Snapshot",
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
        private const val REF_PROPERTY: String = "serialkompat.ref"
        private const val VERIFICATION_GROUP: String = "verification"
    }
}
