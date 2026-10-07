package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.EncodeDefaultMode
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.Subtype
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass

/**
 * The default [SnapshotExtractor]: a vendored, runtime `SerialDescriptor` walk
 * (design §4, spike #6). It reads compatibility-bearing facts straight off the
 * compiled descriptor — the highest-fidelity source, seeing `@SerialName`,
 * `isElementOptional`, nullability, `@JsonNames`, enum values, sealed subtypes,
 * and `SerializersModule`-resolved open polymorphism.
 *
 * The graph is walked breadth-first keyed by contract identity — serial name plus,
 * for a sealed/polymorphic subtype, the base it was reached through (#200) — so
 * cyclic and shared references are captured exactly once and terminate, and
 * subtypes of different bases may share a `@SerialName`. An identity that turns
 * out to carry two different shapes (e.g. generic instantiations) is recorded as
 * an OPAQUE coverage gap.
 *
 * `@EncodeDefault` is not a `@SerialInfo` annotation and so is absent from
 * `getElementAnnotations`; its mode is recovered from the model class's bytecode by
 * [EncodeDefaultReader] instead, and left `null` (unknown) when that fails (#158, §14).
 */
@OptIn(ExperimentalSerializationApi::class)
public object DescriptorSnapshotExtractor : SnapshotExtractor {
    override fun extract(
        roots: Iterable<SerialDescriptor>,
        module: SerializersModule,
        config: SnapshotConfig,
    ): Snapshot = extract(roots, module, config, emptyList())

    /**
     * As [extract], plus [genericRoots] — descriptors for root-only generic types resolved with
     * type-parameter holes (#139). They are walked *after* [roots] and share the same visited-set,
     * so a generic whose serial name was already reached concretely in [roots] is skipped
     * (fill-if-absent): the concrete instantiation wins, which avoids both snapshot churn and
     * orphaning the concrete type arguments the primary walk enqueued.
     */
    public fun extract(
        roots: Iterable<SerialDescriptor>,
        module: SerializersModule,
        config: SnapshotConfig,
        genericRoots: Iterable<SerialDescriptor>,
    ): Snapshot {
        val openPoly = collectOpenSubtypes(module)
        val walk = Walk()
        drain(ArrayDeque(roots.map { Node(it, base = null, root = true) }), config, openPoly, walk, true)
        settleRoots(walk)
        // Fill-if-absent (#139): a hole-based generic never competes with a concrete shape already
        // recorded under its name, so it's skipped rather than treated as a collision.
        drain(ArrayDeque(genericRoots.map { Node(it, base = null, root = false) }), config, openPoly, walk, false)
        return Snapshot(walk.contracts.values.toList(), config)
    }

    /** A contract's identity: its serial name plus the base it was reached through as a subtype. */
    private data class Key(
        val serialName: String,
        val base: String?,
    )

    /**
     * A descriptor to walk. [base] is the serial name of the sealed/polymorphic contract that listed
     * it as a subtype (else `null`); [root] marks a descriptor handed in as a root rather than reached.
     */
    private class Node(
        val descriptor: SerialDescriptor,
        val base: String?,
        val root: Boolean,
    )

    /**
     * Walk state. [shapes] holds every distinct contract seen per identity. [contracts] holds the
     * recorded contract per identity, in first-visit order. [pendingRoots] holds root contracts
     * whose recording waits for the walk to finish (see [settleRoots]).
     */
    private class Walk {
        val shapes = mutableMapOf<Key, MutableSet<Contract>>()
        val contracts = linkedMapOf<Key, Contract>()
        val pendingRoots = mutableListOf<Contract>()
    }

    /**
     * Walks [queue] breadth-first into [walk], keyed by contract identity.
     *
     * A serial name doesn't identify a shape uniquely: generic type arguments are dropped
     * (`Page<Item>` and `Page<User>` are both `Page`). Sealed subtypes sharing a `@SerialName` across
     * bases are told apart by their base (#200). When [detectCollisions] is set, a revisit is
     * re-analysed. An identical shape (the usual cycle/shared-type case) is skipped. A *different*
     * shape replaces the recorded contract with an OPAQUE coverage gap, because keeping only the
     * first would leave the other unchecked (design §10). The new shape's references are still
     * walked, so types reachable only through it are not dropped.
     *
     * A root is analysed and its references walked straight away, but its own recording is
     * deferred to [settleRoots]: discovery hands every `@Serializable` class in as a root, sealed
     * subtypes included, and only once the walk is done is it known whether a root is one.
     */
    private fun drain(
        queue: ArrayDeque<Node>,
        config: SnapshotConfig,
        openPoly: OpenPolymorphism,
        walk: Walk,
        detectCollisions: Boolean,
    ) {
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val descriptor = node.descriptor
            // Even the serial name can fail to resolve (a lazily-built descriptor whose class is
            // missing at runtime); key such a node deterministically so it still surfaces as a gap.
            val serialName =
                guarded { contractName(descriptor) } ?: "<unresolvable:${descriptor::class.java.name}>"
            val key = Key(serialName, node.base)
            if (key in walk.shapes && !detectCollisions) continue

            // A gate must never crash and never silently drop a type it can't
            // analyze (design §10): an unknown kind or a walk failure becomes an
            // explicit OPAQUE coverage gap instead.
            val referenced = mutableListOf<SerialDescriptor>()
            val subtypes = mutableListOf<SerialDescriptor>()
            val contract =
                guarded { contractOf(descriptor, serialName, config, openPoly, referenced, subtypes) }
                    ?.let { if (node.base == null) it else rebase(it, node.base) }
                    ?: Contract(serialName, ContractKind.OPAQUE, base = node.base).also {
                        referenced.clear()
                        subtypes.clear()
                    }
            val next =
                referenced.map { Node(it, base = null, root = false) } +
                    subtypes.map { Node(it, base = serialName, root = false) }
            if (node.root && key !in walk.shapes) {
                walk.pendingRoots += contract
                queue += next
            } else if (record(walk, key, contract)) {
                queue += next
            }
        }
    }

    /**
     * Records [contract] under [key], returning whether its references should be walked: on a first
     * visit, or on a new colliding shape (bounded, so a generic that recursively instantiates itself
     * with ever-deeper type arguments, e.g. `Node<T>(val next: Node<List<T>>?)`, still terminates).
     */
    private fun record(
        walk: Walk,
        key: Key,
        contract: Contract,
    ): Boolean {
        val seen = walk.shapes[key]
        return when {
            seen == null -> {
                walk.shapes[key] = mutableSetOf(contract)
                walk.contracts[key] = contract
                true
            }

            !seen.add(contract) -> {
                false // an identical revisit: already recorded
            }

            else -> {
                if (walk.contracts[key]?.kind != ContractKind.OPAQUE) {
                    System.err.println(
                        "serialkompat: '${contract.qualifiedName}' resolves to more than one shape (e.g. a " +
                            "generic used with different type arguments, or distinct types sharing a " +
                            "@SerialName); recording it as an opaque coverage gap.",
                    )
                }
                walk.contracts[key] = Contract(key.serialName, ContractKind.OPAQUE, base = key.base)
                seen.size <= MAX_SHAPES_PER_NAME
            }
        }
    }

    /**
     * Records the deferred roots. A root that the walk also recorded, with the same shape, as a
     * subtype of some base *is* that subtype: it is checked under its base and not recorded again
     * unqualified (#200). Otherwise, sealed subtypes discovered as roots would all land on one bare
     * identity and collide whenever two bases reuse a subtype name. Any other root is recorded
     * unqualified as usual; its references were already walked.
     */
    private fun settleRoots(walk: Walk) {
        val subtypeShapes =
            walk.contracts.values
                .filter { it.base != null }
                .map { rebase(it, null) }
                .toSet()
        for (contract in walk.pendingRoots) {
            if (contract in subtypeShapes) continue
            record(walk, Key(contract.serialName, null), contract)
        }
        walk.pendingRoots.clear()
    }

    /** [contract] with its [Contract.base] replaced by [base]. */
    private fun rebase(
        contract: Contract,
        base: String?,
    ): Contract =
        Contract(
            serialName = contract.serialName,
            kind = contract.kind,
            elements = contract.elements,
            enumValues = contract.enumValues,
            discriminator = contract.discriminator,
            subtypes = contract.subtypes,
            hasPolymorphicDefault = contract.hasPolymorphicDefault,
            base = base,
        )

    /** How many distinct shapes of one contract identity have their references walked. */
    private const val MAX_SHAPES_PER_NAME = 8

    /**
     * Runs [block], mapping any failure to `null` so the caller records an OPAQUE gap. Walking a
     * descriptor resolves serializers lazily, so a broken model surfaces as an [Error] as often as
     * an [Exception]: [NoClassDefFoundError] for a type missing at runtime,
     * [ExceptionInInitializerError] for a serializer whose static init throws. Both are per-type
     * failures and must not abort the whole extraction. A [VirtualMachineError] (out of memory,
     * stack overflow) is not about one type, so it still propagates.
     */
    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (error: VirtualMachineError) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
            null
        }

    /**
     * Builds the contract for [descriptor], appending any referenced descriptors
     * that must themselves be walked to [referenced], and a sealed/polymorphic
     * base's subtype descriptors to [subtypes] (walked qualified by this base).
     * Returns `null` for kinds that are element types rather than named contracts.
     */
    private fun contractOf(
        descriptor: SerialDescriptor,
        serialName: String,
        config: SnapshotConfig,
        openPoly: OpenPolymorphism,
        referenced: MutableList<SerialDescriptor>,
        subtypes: MutableList<SerialDescriptor>,
    ): Contract? =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val kind = if (descriptor.kind == StructureKind.OBJECT) ContractKind.OBJECT else ContractKind.CLASS
                val encodeDefaults = EncodeDefaultReader.modes(descriptor)
                val elements =
                    (0 until descriptor.elementsCount).map { i ->
                        referenced += referencedContracts(descriptor.getElementDescriptor(i))
                        elementOf(descriptor, i, encodeDefaults[i])
                    }
                Contract(serialName, kind, elements = elements)
            }

            SerialKind.ENUM -> {
                Contract(serialName, ContractKind.ENUM, enumValues = descriptor.elementNames.toList())
            }

            PolymorphicKind.SEALED -> {
                val subtypeDescriptors = descriptor.getElementDescriptor(1).elementDescriptors.toList()
                subtypes += subtypeDescriptors
                Contract(
                    serialName,
                    ContractKind.SEALED,
                    discriminator = discriminatorOf(descriptor, config),
                    subtypes = subtypeDescriptors.map { Subtype(contractName(it), contractName(it)) },
                    // SealedClassSerializer falls back to the module's polymorphic default for an
                    // unknown discriminator too — the `Unknown` sentinel idiom on sealed bases.
                    hasPolymorphicDefault = openPoly.hasDefault(descriptor),
                )
            }

            PolymorphicKind.OPEN -> {
                val baseClass = descriptor.capturedKClass
                val subtypeDescriptors = baseClass?.let { openPoly.subtypes[it] }.orEmpty()
                // An open base's subtypes are only knowable from the module. None visible (an
                // unregistered base, the wrong module, or no captured class) means the hierarchy
                // was not analysed. An empty POLYMORPHIC would read as fully analysed and let every
                // subtype change pass unseen, so record a coverage gap instead (design §10).
                if (subtypeDescriptors.isEmpty()) return Contract(serialName, ContractKind.OPAQUE)
                subtypes += subtypeDescriptors
                Contract(
                    serialName,
                    ContractKind.POLYMORPHIC,
                    discriminator = discriminatorOf(descriptor, config),
                    subtypes = subtypeDescriptors.map { Subtype(contractName(it), contractName(it)) },
                    // A registered default deserializer is a read-side tolerance: an unknown subtype's
                    // discriminator coerces to the sentinel instead of throwing (#128).
                    hasPolymorphicDefault = baseClass != null && baseClass in openPoly.defaults,
                )
            }

            else -> {
                null
            } // primitives, list/map, contextual — element types, not contracts
        }

    private fun elementOf(
        owner: SerialDescriptor,
        index: Int,
        encodeDefault: EncodeDefaultMode?,
    ): Element {
        val descriptor = owner.getElementDescriptor(index)
        val annotations = owner.getElementAnnotations(index)
        return Element(
            name = owner.getElementName(index),
            // `nullable` is the field's own (Kotlin-level) nullability: it also drives "absent decodes
            // as null" under explicitNulls=false, which a non-null value class wrapper does NOT get even
            // when its underlying value is nullable (MissingFieldException). The underlying `null` that
            // such a wrapper can still put on the wire is recorded in the type instead ("kotlin.String?").
            type = if (descriptor.isNullable) typeRef(descriptor) else typeRefNullable(descriptor),
            optional = owner.isElementOptional(index),
            nullable = descriptor.isNullable,
            jsonNames = annotations.filterIsInstance<JsonNames>().flatMap { it.names.toList() },
            // @EncodeDefault isn't a @SerialInfo annotation, so it comes from bytecode
            // reflection instead (EncodeDefaultReader, #158); null = unknown.
            encodeDefault = encodeDefault,
        )
    }

    /** A canonical, whitespace-free type reference. Nullability of the top-level
     * element is recorded separately on [Element]; nested nullability is kept, including a
     * value class's nullable underlying value (see [wireNullable]). */
    private fun typeRef(descriptor: SerialDescriptor): String {
        // A @JvmInline value class is transparent on the wire: it serializes as its single
        // underlying value, never as a wrapper object. Its type ref is therefore the underlying
        // type, so that swapping a raw primitive for a wire-identical value class (or back) is
        // not misread as a breaking type change (design §14).
        if (descriptor.isInline) return typeRef(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                "List<${typeRefNullable(descriptor.getElementDescriptor(0))}>"
            }

            StructureKind.MAP -> {
                "Map<${typeRefNullable(
                    descriptor.getElementDescriptor(0),
                )},${typeRefNullable(descriptor.getElementDescriptor(1))}>"
            }

            else -> {
                contractName(descriptor)
            }
        }
    }

    private fun typeRefNullable(descriptor: SerialDescriptor): String =
        typeRef(descriptor) + if (wireNullable(descriptor)) "?" else ""

    /**
     * Whether `null` can appear on the wire at this position. A value class is transparent, so
     * `Id(null)` for `value class Id(val raw: String?)` encodes as a bare `null` even when the `Id`
     * itself is non-null. Its nullability is therefore the wrapper's *or* the underlying value's,
     * recursively, matching how [typeRef] unwraps it.
     */
    private fun wireNullable(descriptor: SerialDescriptor): Boolean =
        descriptor.isNullable || (descriptor.isInline && wireNullable(descriptor.getElementDescriptor(0)))

    /** Descriptors reachable from an element that are themselves named contracts. */
    private fun referencedContracts(descriptor: SerialDescriptor): List<SerialDescriptor> {
        // Unwrap value classes to their underlying type's references — a value class wrapping a
        // @Serializable object still needs that object walked; one wrapping a primitive walks nothing.
        if (descriptor.isInline) return referencedContracts(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                referencedContracts(descriptor.getElementDescriptor(0))
            }

            StructureKind.MAP -> {
                referencedContracts(descriptor.getElementDescriptor(0)) +
                    referencedContracts(descriptor.getElementDescriptor(1))
            }

            StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM,
            PolymorphicKind.SEALED, PolymorphicKind.OPEN,
            -> {
                listOf(descriptor)
            }

            // An unresolved @Contextual serializer's runtime shape is invisible to the descriptor
            // walk — exactly the "unanalysable ≠ safe" case (design §10). Walk it so contractOf
            // degrades it to an OPAQUE node and SnapshotDiffer raises a CoverageGap (#131), rather
            // than trusting the ContextualSerializer<T> type ref as if it were a stable wire shape.
            SerialKind.CONTEXTUAL -> {
                listOf(descriptor)
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun discriminatorOf(
        descriptor: SerialDescriptor,
        config: SnapshotConfig,
    ): String =
        descriptor.annotations
            .filterIsInstance<JsonClassDiscriminator>()
            .firstOrNull()
            ?.discriminator
            ?: config.classDiscriminator

    /** The serial name used as a contract's identity and type ref, less any nullable marker. */
    private fun contractName(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?")

    /**
     * A module's open-polymorphic registrations: base class → subtype descriptors, plus the base
     * classes that registered a default deserializer (the read-side fallback for an unknown subtype).
     */
    private data class OpenPolymorphism(
        val subtypes: Map<KClass<*>, List<SerialDescriptor>>,
        val defaults: Set<KClass<*>>,
    ) {
        /**
         * Serial names of the default-registering bases. A sealed descriptor carries no captured class,
         * so it is matched by name; a base whose serializer can't be resolved is simply absent here,
         * which keeps the verdict conservative (no recorded default → forward BREAK).
         */
        val defaultSerialNames: Set<String> by lazy {
            defaults
                .mapNotNull { base -> runCatching { serializerOrNull(base.java)?.descriptor?.serialName }.getOrNull() }
                .toSet()
        }

        fun hasDefault(descriptor: SerialDescriptor): Boolean =
            descriptor.capturedKClass?.let { it in defaults } ?: (contractNameOf(descriptor) in defaultSerialNames)

        private fun contractNameOf(descriptor: SerialDescriptor) = descriptor.serialName.removeSuffix("?")
    }

    /** Flattens a module's polymorphic registrations into [OpenPolymorphism]. */
    private fun collectOpenSubtypes(module: SerializersModule): OpenPolymorphism {
        val subtypes = mutableMapOf<KClass<*>, MutableList<SerialDescriptor>>()
        val defaults = mutableSetOf<KClass<*>>()
        module.dumpTo(
            object : SerializersModuleCollector {
                override fun <T : Any> contextual(
                    kClass: KClass<T>,
                    provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>,
                ) = Unit

                override fun <Base : Any, Sub : Base> polymorphic(
                    baseClass: KClass<Base>,
                    actualClass: KClass<Sub>,
                    actualSerializer: KSerializer<Sub>,
                ) {
                    subtypes.getOrPut(baseClass) { mutableListOf() } += actualSerializer.descriptor
                }

                override fun <Base : Any> polymorphicDefaultSerializer(
                    baseClass: KClass<Base>,
                    defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?,
                ) = Unit

                override fun <Base : Any> polymorphicDefaultDeserializer(
                    baseClass: KClass<Base>,
                    defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?,
                ) {
                    // The fallback used on read for an unknown discriminator — captured as a tolerance
                    // fact on the base's contract (#128), not a walkable subtype descriptor.
                    defaults += baseClass
                }
            },
        )
        return OpenPolymorphism(subtypes, defaults)
    }
}
