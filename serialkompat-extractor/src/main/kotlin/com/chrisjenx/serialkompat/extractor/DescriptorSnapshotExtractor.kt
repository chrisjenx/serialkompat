package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
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
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass

/**
 * The default [SnapshotExtractor]: a vendored, runtime `SerialDescriptor` walk
 * (design §4, spike #6). It reads compatibility-bearing facts straight off the
 * compiled descriptor — the highest-fidelity source, seeing `@SerialName`,
 * `isElementOptional`, nullability, `@JsonNames`, enum values, sealed subtypes,
 * and `SerializersModule`-resolved open polymorphism.
 *
 * The graph is walked breadth-first with a visited-set keyed by serial name, so
 * cyclic and shared references are captured exactly once and terminate.
 *
 * Limitation: `@EncodeDefault` is not a `@SerialInfo` annotation and so is absent
 * from `getElementAnnotations`; Approach A cannot recover its mode (see §14).
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
        module: SerializersModule = EmptySerializersModule(),
        config: SnapshotConfig = SnapshotConfig(),
        genericRoots: Iterable<SerialDescriptor> = emptyList(),
    ): Snapshot {
        val openPoly = collectOpenSubtypes(module)
        val visited = mutableSetOf<String>()
        val contracts = mutableListOf<Contract>()
        drain(ArrayDeque(roots.toList()), config, openPoly, visited, contracts)
        drain(ArrayDeque(genericRoots.toList()), config, openPoly, visited, contracts)
        return Snapshot(contracts, config)
    }

    /** Walks [queue] breadth-first into [contracts], deduping by serial name via [visited]. */
    private fun drain(
        queue: ArrayDeque<SerialDescriptor>,
        config: SnapshotConfig,
        openPoly: OpenPolymorphism,
        visited: MutableSet<String>,
        contracts: MutableList<Contract>,
    ) {
        while (queue.isNotEmpty()) {
            val descriptor = queue.removeFirst()
            val serialName = contractName(descriptor)
            if (!visited.add(serialName)) continue

            // A gate must never crash and never silently drop a type it can't
            // analyze (design §10): an unknown kind or a walk failure becomes an
            // explicit OPAQUE coverage gap instead.
            val referenced = mutableListOf<SerialDescriptor>()
            val contract =
                try {
                    contractOf(descriptor, serialName, config, openPoly, referenced)
                        ?: Contract(serialName, ContractKind.OPAQUE)
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    referenced.clear()
                    Contract(serialName, ContractKind.OPAQUE)
                }
            contracts += contract
            queue += referenced
        }
    }

    /**
     * Builds the contract for [descriptor], appending any referenced descriptors
     * that must themselves be walked to [referenced]. Returns `null` for kinds
     * that are element types rather than named contracts.
     */
    private fun contractOf(
        descriptor: SerialDescriptor,
        serialName: String,
        config: SnapshotConfig,
        openPoly: OpenPolymorphism,
        referenced: MutableList<SerialDescriptor>,
    ): Contract? =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val kind = if (descriptor.kind == StructureKind.OBJECT) ContractKind.OBJECT else ContractKind.CLASS
                val elements =
                    (0 until descriptor.elementsCount).map { i ->
                        referenced += referencedContracts(descriptor.getElementDescriptor(i))
                        elementOf(descriptor, i)
                    }
                Contract(serialName, kind, elements = elements)
            }

            SerialKind.ENUM ->
                Contract(serialName, ContractKind.ENUM, enumValues = descriptor.elementNames.toList())

            PolymorphicKind.SEALED -> {
                val subtypeDescriptors = descriptor.getElementDescriptor(1).elementDescriptors.toList()
                referenced += subtypeDescriptors
                Contract(
                    serialName,
                    ContractKind.SEALED,
                    discriminator = discriminatorOf(descriptor, config),
                    subtypes = subtypeDescriptors.map { Subtype(contractName(it), contractName(it)) },
                )
            }

            PolymorphicKind.OPEN -> {
                val baseClass = descriptor.capturedKClass
                val subtypeDescriptors = baseClass?.let { openPoly.subtypes[it] }.orEmpty()
                referenced += subtypeDescriptors
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

            else -> null // primitives, list/map, contextual — element types, not contracts
        }

    private fun elementOf(
        owner: SerialDescriptor,
        index: Int,
    ): Element {
        val descriptor = owner.getElementDescriptor(index)
        val annotations = owner.getElementAnnotations(index)
        return Element(
            name = owner.getElementName(index),
            type = typeRef(descriptor),
            optional = owner.isElementOptional(index),
            nullable = descriptor.isNullable,
            jsonNames = annotations.filterIsInstance<JsonNames>().flatMap { it.names.toList() },
            // NOTE: @EncodeDefault is not a @SerialInfo annotation, so it does not
            // appear in getElementAnnotations — Approach A (runtime descriptor)
            // cannot recover its mode. Left null; a compiler-plugin extractor could read it (§14).
            encodeDefault = null,
        )
    }

    /** A canonical, whitespace-free type reference. Nullability of the top-level
     * element is recorded separately on [Element]; nested nullability is kept. */
    private fun typeRef(descriptor: SerialDescriptor): String {
        // A @JvmInline value class is transparent on the wire: it serializes as its single
        // underlying value, never as a wrapper object. Its type ref is therefore the underlying
        // type, so that swapping a raw primitive for a wire-identical value class (or back) is
        // not misread as a breaking type change (design §14).
        if (descriptor.isInline) return typeRef(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            StructureKind.LIST ->
                "List<${typeRefNullable(descriptor.getElementDescriptor(0))}>"
            StructureKind.MAP ->
                "Map<${typeRefNullable(
                    descriptor.getElementDescriptor(0),
                )},${typeRefNullable(descriptor.getElementDescriptor(1))}>"
            else -> contractName(descriptor)
        }
    }

    private fun typeRefNullable(descriptor: SerialDescriptor): String =
        typeRef(descriptor) + if (descriptor.isNullable) "?" else ""

    /** Descriptors reachable from an element that are themselves named contracts. */
    private fun referencedContracts(descriptor: SerialDescriptor): List<SerialDescriptor> {
        // Unwrap value classes to their underlying type's references — a value class wrapping a
        // @Serializable object still needs that object walked; one wrapping a primitive walks nothing.
        if (descriptor.isInline) return referencedContracts(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            StructureKind.LIST -> referencedContracts(descriptor.getElementDescriptor(0))
            StructureKind.MAP ->
                referencedContracts(descriptor.getElementDescriptor(0)) +
                    referencedContracts(descriptor.getElementDescriptor(1))
            StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM,
            PolymorphicKind.SEALED, PolymorphicKind.OPEN,
            -> listOf(descriptor)
            // An unresolved @Contextual serializer's runtime shape is invisible to the descriptor
            // walk — exactly the "unanalysable ≠ safe" case (design §10). Walk it so contractOf
            // degrades it to an OPAQUE node and SnapshotDiffer raises a CoverageGap (#131), rather
            // than trusting the ContextualSerializer<T> type ref as if it were a stable wire shape.
            SerialKind.CONTEXTUAL -> listOf(descriptor)
            else -> emptyList()
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
    )

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
