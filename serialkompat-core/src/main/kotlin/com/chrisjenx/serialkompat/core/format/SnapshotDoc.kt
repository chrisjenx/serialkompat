package com.chrisjenx.serialkompat.core.format

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig

/*
 * The snapshot↔doc mappers: ALL domain knowledge lives here — which body
 * shape each ContractKind emits, the fixed alphabetical @config key order,
 * and the exact element token order. Layout and escaping live in the
 * writer/reader; the mappers only choose token kinds.
 */

/** Maps a [Snapshot] to its canonical document: model-sorted contracts, then the `@config` block. */
internal fun snapshotToDoc(snapshot: Snapshot): FormatDoc =
    FormatDoc(snapshot.contracts.map(::contractBlock) + configBlock(snapshot.config))

private fun contractBlock(contract: Contract): Block {
    val header =
        buildList {
            add(Token.Word("@contract"))
            add(Token.Word(contract.serialName))
            add(Token.KeyValue("kind", contract.kind.name))
            contract.discriminator?.let { add(Token.KeyValue("discriminator", it)) }
            // Emitted only when set, so every existing snapshot round-trips
            // unchanged and old readers never see the token (#128).
            if (contract.hasPolymorphicDefault) add(Token.KeyValue("polymorphicDefault", "true"))
        }
    val body =
        when (contract.kind) {
            ContractKind.ENUM -> listOf(valuesLine(contract.enumValues))
            ContractKind.SEALED, ContractKind.POLYMORPHIC ->
                listOf(Line(1, listOf(Token.Word("subtypes:")))) +
                    contract.subtypes.map { subtypeLine(it.discriminatorValue, it.serialName) }
            ContractKind.CLASS, ContractKind.OBJECT -> contract.elements.map(::elementLine)
            ContractKind.OPAQUE -> emptyList() // no analyzable body
        }
    return Block(listOf(Line(0, header)) + body)
}

private fun valuesLine(enumValues: List<String>): Line = Line(1, listOf(Token.KeyList("values", enumValues)))

private fun subtypeLine(
    discriminatorValue: String,
    serialName: String,
): Line = Line(2, listOf(Token.ArrowPair(discriminatorValue, serialName)))

private fun elementLine(element: Element): Line =
    Line(
        1,
        buildList {
            add(Token.FieldRef(element.name, element.type))
            if (element.optional) add(Token.Word("optional"))
            if (element.nullable) add(Token.Word("nullable"))
            if (element.jsonNames.isNotEmpty()) add(Token.KeyList("jsonNames", element.jsonNames))
            element.encodeDefault?.let { add(Token.KeyValue("encodeDefault", it.name)) }
        },
    )

private fun configBlock(config: SnapshotConfig): Block =
    Block(
        listOf(
            Line(0, listOf(Token.Word("@config"))),
            // Fixed alphabetical key order for byte-stability.
            configLine("classDiscriminator", config.classDiscriminator),
            configLine("classDiscriminatorMode", config.classDiscriminatorMode),
            configLine("coerceInputValues", config.coerceInputValues.toString()),
            configLine("encodeDefaults", config.encodeDefaults.toString()),
            configLine("explicitNulls", config.explicitNulls.toString()),
            configLine("ignoreUnknownKeys", config.ignoreUnknownKeys.toString()),
            configLine("namingStrategy", config.namingStrategy),
            configLine("useAlternativeNames", config.useAlternativeNames.toString()),
        ),
    )

private fun configLine(
    key: String,
    value: String,
): Line = Line(1, listOf(Token.KeyValue(key, value)))
