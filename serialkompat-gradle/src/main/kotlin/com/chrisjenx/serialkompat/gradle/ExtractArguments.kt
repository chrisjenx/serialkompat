package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.extractor.DiscoveryMode
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * The `serialkompatExtract` command line, as a typed [CommandLineArgumentProvider] so Gradle can
 * fingerprint it. Its annotated properties — not the rendered arguments, which carry absolute
 * paths — are the task's cache key, so the task is cacheable and relocatable: a checkout at a
 * different path (e.g. the baseline git worktree) hits the same build-cache entry.
 */
internal abstract class ExtractArguments : CommandLineArgumentProvider {
    @get:Input
    abstract val types: ListProperty<String>

    @get:Input
    abstract val discovery: Property<DiscoveryMode>

    @get:Input
    @get:Optional
    abstract val jsonInstance: Property<String>

    /** The module's compiled class dirs, scanned for `@Serializable` roots under discovery. */
    @get:Classpath
    abstract val scanDirs: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        buildList {
            val types = types.get()
            if (types.isNotEmpty()) {
                add("--types")
                add(types.joinToString(","))
            }
            // Discovery is only in play when `types` is empty (docs/configuration.md's documented
            // precedence) — matches SchemaExtractionMain's own `typeNames.ifEmpty { ... }` filtering,
            // so the plugin never pays for (or reports on) a scan whose result would be discarded.
            val discovery = discovery.get()
            if (discovery != DiscoveryMode.EXPLICIT && types.isEmpty()) {
                add("--discovery")
                add(if (discovery == DiscoveryMode.OPT_OUT) "opt-out" else "opt-in")
                // No Java `main` source set and no KMP jvm() target (e.g. an aggregator project)
                // leaves scanDirs empty; an empty --scan-classes would hit the extractor's
                // `require(types.isNotEmpty() || scanDirs.isNotEmpty())`. The extractor already
                // degrades to manifest-only discovery when it is absent, so omit it.
                val dirs = scanDirs.files
                if (dirs.isNotEmpty()) {
                    add("--scan-classes")
                    add(dirs.joinToString(File.pathSeparator) { it.absolutePath })
                }
            }
            add("--out")
            add(output.get().asFile.absolutePath)
            jsonInstance.orNull?.let {
                add("--json")
                add(it)
            }
        }
}
