package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.full.createType

/**
 * The program the Gradle `serialkompatExtract` task launches on the *target
 * project's* runtime classpath (design §4). Running on that classpath is what
 * lets it `Class.forName` the project's `@Serializable` types, obtain their real
 * compiled serializers, and walk the descriptors at highest fidelity.
 *
 * It resolves each named type to a serializer, extracts a [com.chrisjenx.serialkompat.core.Snapshot],
 * and writes the canonical text form to a file for the check to consume.
 */
public object SchemaExtractionMain {
    /**
     * Extracts [typeNames] (fully-qualified `@Serializable` class names) into a
     * snapshot written to [output]. If [jsonInstanceFqn] names a reachable `Json`
     * instance its configuration is read; otherwise a conservative default is
     * assumed with a warning (design §6 resolution order).
     */
    public fun run(
        typeNames: List<String>,
        jsonInstanceFqn: String?,
        output: File,
    ) {
        val json = jsonInstanceFqn?.let(::loadJson) ?: Json
        if (jsonInstanceFqn != null && loadJson(jsonInstanceFqn) == null) {
            System.err.println(
                "serialkompat: could not load Json instance '$jsonInstanceFqn'; assuming default config.",
            )
        }
        val config = if (json === Json) SnapshotConfig() else JsonConfigReader.read(json)
        // Fall back to discovered types when none are configured explicitly (see [TYPES_RESOURCE]).
        val effectiveTypes = typeNames.ifEmpty { discoverTypeNames() }

        // Resolve each type independently. A single unresolvable/broken type (stale manifest entry,
        // renamed class, missing transitive dependency, a generic that needs type args) must NEVER
        // abort the whole extraction — that would drop every type. It degrades to an OPAQUE coverage
        // gap keyed by its FQN; the gate then surfaces a WARN rather than crashing or silently
        // dropping the lot ("the extractor must never throw on a model it can't analyse", design §10).
        val descriptors = mutableListOf<SerialDescriptor>()
        val opaque = mutableListOf<Contract>()
        for (name in effectiveTypes) {
            val descriptor =
                runCatching { serializer(Class.forName(name).kotlin.createType()).descriptor }.getOrNull()
            if (descriptor != null) {
                descriptors += descriptor
            } else {
                System.err.println(
                    "serialkompat: could not resolve type '$name'; recording it as an opaque coverage gap.",
                )
                opaque += Contract(name, ContractKind.OPAQUE)
            }
        }

        val extracted = DescriptorSnapshotExtractor.extract(descriptors, json.serializersModule, config)
        val snapshot = Snapshot(extracted.contracts + opaque, config)
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(SnapshotFormat.serialize(snapshot))
    }

    /** CLI shim: `--types a,b,c --out path [--json fqn]`. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parseOptions(args)
        val types =
            options["types"]
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
        val output = options["out"] ?: error("serialkompat: --out is required")
        require(types.isNotEmpty()) { "serialkompat: --types is required" }
        run(types, options["json"], File(output))
    }

    /**
     * Classpath manifest of discovered `@Serializable` FQNs, one per line (blank
     * lines and `#` comments ignored). This is a producer-agnostic contract: the
     * file may be authored by hand or emitted by a build-time discovery step. A
     * Kotlin compiler plugin producer is the sanctioned automated route and is
     * tracked separately (design §4, issue #22); KSP is explicitly not used.
     */
    public const val TYPES_RESOURCE: String = "META-INF/serialkompat/serializable-types.txt"

    /**
     * Reads discovered `@Serializable` type names from every [TYPES_RESOURCE] on
     * [loader]'s classpath, ignoring blank lines and `#` comments; sorted + deduped.
     */
    public fun discoverTypeNames(
        loader: ClassLoader =
            Thread.currentThread().contextClassLoader ?: SchemaExtractionMain::class.java.classLoader,
    ): List<String> =
        loader
            .getResources(TYPES_RESOURCE)
            .asSequence()
            .flatMap { url ->
                url.openStream().bufferedReader().use { it.readLines() }
            }.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
            .sorted()
            .toList()

    private fun parseOptions(args: Array<String>): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size - 1) {
            if (args[index].startsWith("--")) options[args[index].removePrefix("--")] = args[index + 1]
            index += 2
        }
        return options
    }

    /**
     * Best-effort reflective load of a `Json` instance named `owner.member`,
     * supporting an `object`'s property or a file-level `val`. Returns `null` if
     * it can't be resolved (the caller then assumes defaults).
     */
    private fun loadJson(fqn: String): Json? =
        runCatching {
            val lastDot = fqn.lastIndexOf('.')
            if (lastDot <= 0) return null
            val owner = Class.forName(fqn.substring(0, lastDot))
            val member = fqn.substring(lastDot + 1)
            val instance = runCatching { owner.getField("INSTANCE").get(null) }.getOrNull()
            val getter = "get" + member.replaceFirstChar(Char::uppercase)
            val value =
                runCatching { owner.getMethod(getter).invoke(instance) }.getOrNull()
                    ?: runCatching {
                        owner.getDeclaredField(member).also { it.isAccessible = true }.get(instance)
                    }.getOrNull()
            value as? Json
        }.getOrNull()
}
