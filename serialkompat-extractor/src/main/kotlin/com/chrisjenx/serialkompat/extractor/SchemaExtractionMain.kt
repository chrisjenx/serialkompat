package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
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
     *
     * When [typeNames] is empty, types are discovered instead: the classpath
     * manifest (see [TYPES_RESOURCE]) unioned with a class-dir scan of [scanDirs]
     * (issue #55). Generic classes found by the scan are resolved as roots by
     * extracting their descriptor with type-parameter holes (issue #139): each
     * contributes its own `CLASS` contract whose type-parameter positions render
     * as a stable sentinel, so its envelope fields are checked while the holes stay
     * covered at concrete use sites. A generic sealed/polymorphic hierarchy is out
     * of scope and degrades to an OPAQUE coverage gap. Unreadable class files
     * degrade to OPAQUE
     * coverage gaps whenever [scanDirs] were scanned, even alongside explicit
     * [typeNames].
     *
     * [discovery] refines the *scanned* candidates (issue #115): `OPT_OUT` drops types
     * carrying `@SerialkompatIgnore`; `OPT_IN` keeps only `@SerialkompatChecked` types.
     * Manifest entries and explicit [typeNames] are deliberate acts and bypass the filter.
     */
    @OptIn(ExperimentalSerializationApi::class)
    public fun run(
        typeNames: List<String>,
        jsonInstanceFqn: String?,
        output: File,
        scanDirs: List<File> = emptyList(),
        discovery: DiscoveryMode = DiscoveryMode.EXPLICIT,
    ) {
        val json = jsonInstanceFqn?.let(::loadJson) ?: Json
        if (jsonInstanceFqn != null && loadJson(jsonInstanceFqn) == null) {
            System.err.println(
                "serialkompat: could not load Json instance '$jsonInstanceFqn'; assuming default config.",
            )
        }
        val config = if (json === Json) SnapshotConfig() else JsonConfigReader.read(json)
        val scan = SerializableClassScanner.scan(scanDirs)
        if (scan.skippedGenerics.isNotEmpty()) {
            System.err.println(
                "serialkompat: resolving ${scan.skippedGenerics.size} generic type(s) as scan roots with " +
                    "type-parameter holes (envelope fields checked; generic sealed/polymorphic degrade to " +
                    "OPAQUE): " +
                    scan.skippedGenerics.joinToString(", "),
            )
        }
        // Discovery-mode filter (issue #115): applies to *scanned* candidates only.
        // Manifest entries and explicit typeNames are deliberate acts and bypass it.
        val ignored = scan.ignored.toSet()
        val optedIn = scan.optedIn.toSet()
        // Generic roots (skippedGenerics) are candidates too — the extractor resolves them with
        // holes (#139). Discovery filtering applies to the whole scanned set.
        val allScanned = scan.typeNames + scan.skippedGenerics
        val scannedRoots =
            when (discovery) {
                DiscoveryMode.EXPLICIT -> allScanned
                DiscoveryMode.OPT_OUT -> allScanned.filterNot { it in ignored }
                DiscoveryMode.OPT_IN -> allScanned.filter { it in optedIn }
            }
        if (discovery == DiscoveryMode.OPT_OUT && scan.ignored.isNotEmpty()) {
            System.err.println(
                "serialkompat: ignoring ${scan.ignored.size} type(s) via @SerialkompatIgnore: " +
                    scan.ignored.joinToString(", "),
            )
        }
        if (discovery == DiscoveryMode.OPT_IN) {
            val notOptedIn = allScanned.size - scannedRoots.size
            if (notOptedIn > 0) {
                System.err.println(
                    "serialkompat: OPT_IN discovery — $notOptedIn discovered type(s) not yet " +
                        "@SerialkompatChecked are NOT being checked.",
                )
            }
        }
        // Fall back to discovered types when none are configured explicitly: the
        // classpath manifest (see [TYPES_RESOURCE]) unioned with the class-dir scan.
        val effectiveTypes =
            typeNames.ifEmpty { (discoverTypeNames() + scannedRoots).distinct().sorted() }
        // Discovery ran but turned up nothing: say so, rather than silently emitting an empty
        // snapshot that reads downstream as "no checked types" (silence must never read as coverage).
        if (typeNames.isEmpty() && discovery != DiscoveryMode.EXPLICIT && effectiveTypes.isEmpty()) {
            System.err.println(
                "serialkompat: $discovery discovery found 0 types (no manifest entries and no " +
                    "matching scanned classes); the snapshot will be empty.",
            )
        }

        // Resolve each type independently. A single unresolvable/broken type (stale manifest entry,
        // renamed class, missing transitive dependency, a generic that needs type args) must NEVER
        // abort the whole extraction — that would drop every type. It degrades to an OPAQUE coverage
        // gap keyed by its FQN; the gate then surfaces a WARN rather than crashing or silently
        // dropping the lot ("the extractor must never throw on a model it can't analyse", design §10).
        val descriptors = mutableListOf<SerialDescriptor>()
        val genericRoots = mutableListOf<SerialDescriptor>()
        val opaque = mutableListOf<Contract>()
        for (name in effectiveTypes) {
            val kClass = runCatching { Class.forName(name).kotlin }.getOrNull()
            val generic = kClass != null && kClass.typeParameters.isNotEmpty()
            // Concrete: serializer(kType). Generic: resolve with hole placeholders first, then filter
            // to plain classes only — a generic sealed/polymorphic hierarchy is out of scope this cut
            // and degrades to OPAQUE, keyed by its real wire serialName (not its FQN) since the
            // descriptor did resolve (design §10 never-throw: any failure also degrades, never crashes).
            val rawDescriptor =
                runCatching {
                    when {
                        kClass == null -> null
                        !generic -> serializer(kClass.createType()).descriptor
                        else -> {
                            val holes = List(kClass.typeParameters.size) { HoleSerializer(it) }
                            serializer(kClass, holes, false).descriptor
                        }
                    }
                }.getOrNull()
            val descriptor =
                if (generic) rawDescriptor?.takeIf { it.kind == StructureKind.CLASS } else rawDescriptor
            when {
                descriptor == null -> {
                    System.err.println(
                        "serialkompat: could not resolve type '$name'; recording it as an opaque coverage gap.",
                    )
                    opaque += Contract(rawDescriptor?.serialName ?: name, ContractKind.OPAQUE)
                }
                generic -> genericRoots += descriptor
                else -> descriptors += descriptor
            }
        }
        // An unreadable class file is an unanalysable input: unanalysable ≠ safe, so it
        // surfaces as an OPAQUE gap regardless of whether explicit types were configured.
        for (path in scan.unreadable) {
            System.err.println(
                "serialkompat: could not parse class file '$path'; recording it as an opaque coverage gap.",
            )
            opaque += Contract(path, ContractKind.OPAQUE)
        }

        val extracted =
            DescriptorSnapshotExtractor.extract(descriptors, json.serializersModule, config, genericRoots)
        val snapshot = Snapshot(extracted.contracts + opaque, config)
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(SnapshotFormat.serialize(snapshot))
    }

    /**
     * CLI shim: `--types a,b,c | --scan-classes dir1:dir2 --out path [--json fqn]
     * [--discovery explicit|opt-out|opt-in]`.
     * Requires at least one of: `--types`, `--scan-classes`, or a non-`explicit`
     * `--discovery` (which falls back to the classpath manifest, [TYPES_RESOURCE]).
     * `--scan-classes` takes [File.pathSeparator]-separated class directories.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parseOptions(args)
        val types =
            options["types"]
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
        val scanDirs =
            options["scan-classes"]
                ?.split(File.pathSeparator)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map(::File)
                .orEmpty()
        val output = options["out"] ?: error("serialkompat: --out is required")
        val discovery = options["discovery"]?.let(DiscoveryMode::fromCli) ?: DiscoveryMode.EXPLICIT
        // A non-EXPLICIT --discovery falls back to the classpath manifest (TYPES_RESOURCE) even
        // with no --scan-classes (e.g. a root/aggregator project with no compiled classes of its
        // own to scan) — `run()` already handles that combination, so the CLI must not reject it
        // upfront; only a genuinely unconfigured invocation (EXPLICIT, nothing to check) is an error.
        require(types.isNotEmpty() || scanDirs.isNotEmpty() || discovery != DiscoveryMode.EXPLICIT) {
            "serialkompat: --types or --scan-classes is required"
        }
        run(types, options["json"], File(output), scanDirs, discovery)
    }

    /**
     * Classpath manifest of discovered `@Serializable` FQNs, one per line (blank
     * lines and `#` comments ignored). This is a producer-agnostic contract: the
     * file may be authored by hand or emitted by a build-time discovery step. The
     * extractor's own class-dir scan ([SerializableClassScanner], issue #55) is the
     * automated producer; KSP is explicitly not used (#22).
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
