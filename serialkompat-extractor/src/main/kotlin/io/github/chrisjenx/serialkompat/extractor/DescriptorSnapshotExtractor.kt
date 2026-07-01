package io.github.chrisjenx.serialkompat.extractor

import io.github.chrisjenx.serialkompat.core.Contract
import io.github.chrisjenx.serialkompat.core.ContractKind
import io.github.chrisjenx.serialkompat.core.Element
import io.github.chrisjenx.serialkompat.core.Snapshot
import io.github.chrisjenx.serialkompat.core.SnapshotConfig
import io.github.chrisjenx.serialkompat.core.Subtype
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
    ): Snapshot {
        val openSubtypes = collectOpenSubtypes(module)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(roots.toList())
        val contracts = mutableListOf<Contract>()

        while (queue.isNotEmpty()) {
            val descriptor = queue.removeFirst()
            val serialName = contractName(descriptor)
            if (!visited.add(serialName)) continue

            val referenced = mutableListOf<SerialDescriptor>()
            val contract = contractOf(descriptor, serialName, config, openSubtypes, referenced)
            if (contract != null) {
                contracts += contract
                queue += referenced
            }
        }
        return Snapshot(contracts, config)
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
        openSubtypes: Map<KClass<*>, List<SerialDescriptor>>,
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
                val subtypeDescriptors = descriptor.capturedKClass?.let { openSubtypes[it] }.orEmpty()
                referenced += subtypeDescriptors
                Contract(
                    serialName,
                    ContractKind.POLYMORPHIC,
                    discriminator = discriminatorOf(descriptor, config),
                    subtypes = subtypeDescriptors.map { Subtype(contractName(it), contractName(it)) },
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
            // cannot recover its mode. Left null; a KSP extractor could read it (§14).
            encodeDefault = null,
        )
    }

    /** A canonical, whitespace-free type reference. Nullability of the top-level
     * element is recorded separately on [Element]; nested nullability is kept. */
    private fun typeRef(descriptor: SerialDescriptor): String =
        when (descriptor.kind) {
            StructureKind.LIST ->
                "List<${typeRefNullable(descriptor.getElementDescriptor(0))}>"
            StructureKind.MAP ->
                "Map<${typeRefNullable(
                    descriptor.getElementDescriptor(0),
                )},${typeRefNullable(descriptor.getElementDescriptor(1))}>"
            else -> contractName(descriptor)
        }

    private fun typeRefNullable(descriptor: SerialDescriptor): String =
        typeRef(descriptor) + if (descriptor.isNullable) "?" else ""

    /** Descriptors reachable from an element that are themselves named contracts. */
    private fun referencedContracts(descriptor: SerialDescriptor): List<SerialDescriptor> =
        when (descriptor.kind) {
            StructureKind.LIST -> referencedContracts(descriptor.getElementDescriptor(0))
            StructureKind.MAP ->
                referencedContracts(descriptor.getElementDescriptor(0)) +
                    referencedContracts(descriptor.getElementDescriptor(1))
            StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM,
            PolymorphicKind.SEALED, PolymorphicKind.OPEN,
            -> listOf(descriptor)
            else -> emptyList()
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

    /** Flattens a module's polymorphic registrations into base class → subtype descriptors. */
    private fun collectOpenSubtypes(module: SerializersModule): Map<KClass<*>, List<SerialDescriptor>> {
        val subtypes = mutableMapOf<KClass<*>, MutableList<SerialDescriptor>>()
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
                ) = Unit
            },
        )
        return subtypes
    }
}
