package com.chrisjenx.serialkompat.core.format

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.EncodeDefaultMode
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.Subtype
import com.chrisjenx.serialkompat.core.WireFormat

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
            add(Token.Word(FormatGrammar.CONTRACT_MARKER))
            add(Token.Word(contract.serialName))
            add(Token.KeyValue(FormatGrammar.KEY_KIND, contract.kind.name))
            // Emitted only for a subtype (#200); a pre-#200 header has no base= and parses to null.
            contract.base?.let { add(Token.KeyValue(FormatGrammar.KEY_BASE, it)) }
            contract.discriminator?.let { add(Token.KeyValue(FormatGrammar.KEY_DISCRIMINATOR, it)) }
            // Emitted only when set, so every existing snapshot round-trips
            // unchanged and old readers never see the token (#128).
            if (contract.hasPolymorphicDefault) {
                add(Token.KeyValue(FormatGrammar.KEY_POLYMORPHIC_DEFAULT, "true"))
            }
            // CBOR facts, emitted only when set.
            contract.cborArray?.let { add(Token.KeyList(FormatGrammar.KEY_CBOR_ARRAY, it)) }
            if (contract.objectTags.isNotEmpty()) {
                add(Token.KeyList(FormatGrammar.KEY_OBJECT_TAGS, contract.objectTags.map(ULong::toString)))
            }
        }
    val body =
        when (contract.kind) {
            ContractKind.ENUM -> {
                listOf(valuesLine(contract.enumValues))
            }

            ContractKind.SEALED, ContractKind.POLYMORPHIC -> {
                listOf(Line(1, listOf(Token.Word(FormatGrammar.SUBTYPES_MARKER)))) +
                    contract.subtypes.map { subtypeLine(it.discriminatorValue, it.serialName) }
            }

            ContractKind.CLASS, ContractKind.OBJECT -> {
                contract.elements.map(::elementLine)
            }

            ContractKind.OPAQUE -> {
                emptyList()
            } // no analyzable body
        }
    return Block(listOf(Line(0, header)) + body)
}

private fun valuesLine(enumValues: List<String>): Line =
    Line(1, listOf(Token.KeyList(FormatGrammar.KEY_VALUES, enumValues)))

private fun subtypeLine(
    discriminatorValue: String,
    serialName: String,
): Line = Line(2, listOf(Token.ArrowPair(discriminatorValue, serialName)))

private fun elementLine(element: Element): Line =
    Line(
        1,
        buildList {
            add(Token.FieldRef(element.name, element.type))
            if (element.optional) add(Token.Word(FormatGrammar.FLAG_OPTIONAL))
            if (element.nullable) add(Token.Word(FormatGrammar.FLAG_NULLABLE))
            if (element.jsonNames.isNotEmpty()) {
                add(Token.KeyList(FormatGrammar.KEY_JSON_NAMES, element.jsonNames))
            }
            element.encodeDefault?.let { add(Token.KeyValue(FormatGrammar.KEY_ENCODE_DEFAULT, it.name)) }
            // CBOR facts, emitted only when set.
            element.cborLabel?.let { add(Token.KeyValue(FormatGrammar.KEY_CBOR_LABEL, it.toString())) }
            if (element.byteString) add(Token.Word(FormatGrammar.FLAG_BYTE_STRING))
            if (element.keyTags.isNotEmpty()) {
                add(
                    Token.KeyList(FormatGrammar.KEY_KEY_TAGS, element.keyTags.map(ULong::toString)),
                )
            }
            if (element.valueTags.isNotEmpty()) {
                add(Token.KeyList(FormatGrammar.KEY_VALUE_TAGS, element.valueTags.map(ULong::toString)))
            }
        },
    )

private fun configBlock(config: SnapshotConfig): Block =
    when (config.format) {
        WireFormat.JSON -> jsonConfigBlock(config)
        WireFormat.CBOR -> cborConfigBlock(config)
    }

/**
 * A CBOR snapshot writes only the settings `Cbor` has, plus `format=cbor`. The JSON-only fields are
 * fixed for CBOR ([SnapshotConfig.cbor]), so writing them would only mislead a reader of the diff.
 */
private fun cborConfigBlock(config: SnapshotConfig): Block =
    Block(
        listOf(
            Line(0, listOf(Token.Word(FormatGrammar.CONFIG_MARKER))),
            configLine("alwaysUseByteString", config.alwaysUseByteString.toString()),
            configLine("encodeDefaults", config.encodeDefaults.toString()),
            configLine("encodeKeyTags", config.encodeKeyTags.toString()),
            configLine("encodeObjectTags", config.encodeObjectTags.toString()),
            configLine("encodeValueTags", config.encodeValueTags.toString()),
            configLine(FormatGrammar.KEY_FORMAT, config.format.id),
            configLine("ignoreUnknownKeys", config.ignoreUnknownKeys.toString()),
            configLine("preferCborLabelsOverNames", config.preferCborLabelsOverNames.toString()),
            configLine("useDefiniteLengthEncoding", config.useDefiniteLengthEncoding.toString()),
            configLine("verifyKeyTags", config.verifyKeyTags.toString()),
            configLine("verifyObjectTags", config.verifyObjectTags.toString()),
            configLine("verifyValueTags", config.verifyValueTags.toString()),
        ),
    )

/** A JSON snapshot writes no `format` key, so every snapshot from before CBOR support is unchanged. */
private fun jsonConfigBlock(config: SnapshotConfig): Block =
    Block(
        listOf(
            Line(0, listOf(Token.Word(FormatGrammar.CONFIG_MARKER))),
            // Fixed alphabetical key order for byte-stability. Every key is always written; a
            // snapshot from an older version that lacks a key reads it back as its default (configOf).
            configLine("allowComments", config.allowComments.toString()),
            configLine("allowSpecialFloatingPointValues", config.allowSpecialFloatingPointValues.toString()),
            configLine("allowStructuredMapKeys", config.allowStructuredMapKeys.toString()),
            configLine("allowTrailingComma", config.allowTrailingComma.toString()),
            configLine("classDiscriminator", config.classDiscriminator),
            configLine("classDiscriminatorMode", config.classDiscriminatorMode),
            configLine("coerceInputValues", config.coerceInputValues.toString()),
            configLine("decodeEnumsCaseInsensitive", config.decodeEnumsCaseInsensitive.toString()),
            configLine("encodeDefaults", config.encodeDefaults.toString()),
            configLine("explicitNulls", config.explicitNulls.toString()),
            configLine("ignoreUnknownKeys", config.ignoreUnknownKeys.toString()),
            configLine("isLenient", config.isLenient.toString()),
            configLine("namingStrategy", config.namingStrategy),
            configLine("useAlternativeNames", config.useAlternativeNames.toString()),
            configLine("useArrayPolymorphism", config.useArrayPolymorphism.toString()),
        ),
    )

private fun configLine(
    key: String,
    value: String,
): Line = Line(1, listOf(Token.KeyValue(key, value)))

/** Maps a classified document back to the model. The reader guarantees kind-correct tokens. */
internal fun docToSnapshot(doc: FormatDoc): Snapshot {
    val contracts = mutableListOf<Contract>()
    var config = SnapshotConfig()
    for (block in doc.blocks) {
        // readDoc only ever emits @contract / @config blocks (any other marker
        // throws at parse time), so this cast and the marker match are total.
        val marker =
            (
                block.lines
                    .first()
                    .tokens
                    .first() as Token.Word
            ).text
        when (marker) {
            FormatGrammar.CONTRACT_MARKER -> contracts += contractOf(block)
            FormatGrammar.CONFIG_MARKER -> config = configOf(block)
        }
    }
    return Snapshot(contracts, config)
}

private fun contractOf(block: Block): Contract {
    val header = block.lines.first().tokens
    val serialName = (header[1] as Token.Word).text
    val kvs =
        header.filterIsInstance<Token.KeyValue>().associate { it.key to it.value }
    val kind = ContractKind.valueOf(kvs.getValue(FormatGrammar.KEY_KIND)) // reader-validated
    val lists = header.filterIsInstance<Token.KeyList>().associate { it.key to it.values }
    val elements = mutableListOf<Element>()
    var enumValues = emptyList<String>()
    val subtypes = mutableListOf<Subtype>()
    for (line in block.lines.drop(1)) {
        when (val first = line.tokens.first()) {
            is Token.FieldRef -> elements += elementOf(first, line.tokens.drop(1))

            is Token.KeyList -> if (first.key == FormatGrammar.KEY_VALUES) enumValues = first.values

            is Token.ArrowPair -> subtypes += Subtype(first.left, first.right)

            // the `subtypes:` marker
            is Token.Word -> Unit

            // unknown fact: tolerated
            is Token.KeyValue -> Unit
        }
    }
    return Contract(
        serialName = serialName,
        kind = kind,
        elements = elements,
        enumValues = enumValues,
        discriminator = kvs[FormatGrammar.KEY_DISCRIMINATOR],
        subtypes = subtypes,
        hasPolymorphicDefault = kvs[FormatGrammar.KEY_POLYMORPHIC_DEFAULT]?.toBooleanStrict() ?: false,
        base = kvs[FormatGrammar.KEY_BASE],
        cborArray = lists[FormatGrammar.KEY_CBOR_ARRAY],
        objectTags = lists[FormatGrammar.KEY_OBJECT_TAGS].orEmpty().map(String::toULong),
    )
}

private fun elementOf(
    fieldRef: Token.FieldRef,
    trailing: List<Token>,
): Element {
    var optional = false
    var nullable = false
    var jsonNames = emptyList<String>()
    var encodeDefault: EncodeDefaultMode? = null
    var cborLabel: Long? = null
    var byteString = false
    var keyTags = emptyList<ULong>()
    var valueTags = emptyList<ULong>()
    for (token in trailing) {
        when (token) {
            is Token.Word -> {
                when (token.text) {
                    FormatGrammar.FLAG_OPTIONAL -> optional = true
                    FormatGrammar.FLAG_NULLABLE -> nullable = true
                    FormatGrammar.FLAG_BYTE_STRING -> byteString = true
                    else -> Unit // unknown flag: tolerated
                }
            }

            is Token.KeyList -> {
                when (token.key) {
                    FormatGrammar.KEY_JSON_NAMES -> jsonNames = token.values
                    FormatGrammar.KEY_KEY_TAGS -> keyTags = token.values.map(String::toULong)
                    FormatGrammar.KEY_VALUE_TAGS -> valueTags = token.values.map(String::toULong)
                }
            }

            is Token.KeyValue -> {
                when (token.key) {
                    FormatGrammar.KEY_ENCODE_DEFAULT -> encodeDefault = EncodeDefaultMode.valueOf(token.value)
                    FormatGrammar.KEY_CBOR_LABEL -> cborLabel = token.value.toLong()
                }
            }

            else -> {}
        }
    }
    return Element(
        fieldRef.name,
        fieldRef.type,
        optional,
        nullable,
        jsonNames,
        encodeDefault,
        cborLabel = cborLabel,
        byteString = byteString,
        keyTags = keyTags,
        valueTags = valueTags,
    )
}

private fun configOf(block: Block): SnapshotConfig {
    val values =
        block.lines
            .drop(1)
            .flatMap { it.tokens }
            .filterIsInstance<Token.KeyValue>()
            .associate { it.key to it.value }
    // Every other unknown key is tolerated, but an unknown format must fail closed: reading it as
    // JSON would judge it by the wrong rules.
    val format =
        values[FormatGrammar.KEY_FORMAT]?.let { id ->
            requireNotNull(WireFormat.fromId(id)) { "unknown snapshot format '$id'" }
        } ?: WireFormat.JSON
    val defaults = if (format == WireFormat.CBOR) SnapshotConfig.cbor() else SnapshotConfig()

    // A key absent from an older snapshot (written before that flag was captured) reads as the
    // kotlinx default, so it diffs as no change against a current snapshot of the same Json.
    fun flag(
        key: String,
        default: Boolean,
    ): Boolean = values[key]?.toBooleanStrict() ?: default
    if (format == WireFormat.CBOR) {
        return SnapshotConfig.cbor(
            ignoreUnknownKeys = flag("ignoreUnknownKeys", defaults.ignoreUnknownKeys),
            encodeDefaults = flag("encodeDefaults", defaults.encodeDefaults),
            alwaysUseByteString = flag("alwaysUseByteString", defaults.alwaysUseByteString),
            preferCborLabelsOverNames = flag("preferCborLabelsOverNames", defaults.preferCborLabelsOverNames),
            useDefiniteLengthEncoding = flag("useDefiniteLengthEncoding", defaults.useDefiniteLengthEncoding),
            encodeKeyTags = flag("encodeKeyTags", defaults.encodeKeyTags),
            encodeValueTags = flag("encodeValueTags", defaults.encodeValueTags),
            encodeObjectTags = flag("encodeObjectTags", defaults.encodeObjectTags),
            verifyKeyTags = flag("verifyKeyTags", defaults.verifyKeyTags),
            verifyValueTags = flag("verifyValueTags", defaults.verifyValueTags),
            verifyObjectTags = flag("verifyObjectTags", defaults.verifyObjectTags),
        )
    }
    return SnapshotConfig(
        namingStrategy = values["namingStrategy"] ?: defaults.namingStrategy,
        classDiscriminator = values["classDiscriminator"] ?: defaults.classDiscriminator,
        classDiscriminatorMode = values["classDiscriminatorMode"] ?: defaults.classDiscriminatorMode,
        ignoreUnknownKeys = values["ignoreUnknownKeys"]?.toBooleanStrict() ?: defaults.ignoreUnknownKeys,
        encodeDefaults = values["encodeDefaults"]?.toBooleanStrict() ?: defaults.encodeDefaults,
        explicitNulls = values["explicitNulls"]?.toBooleanStrict() ?: defaults.explicitNulls,
        coerceInputValues = values["coerceInputValues"]?.toBooleanStrict() ?: defaults.coerceInputValues,
        useAlternativeNames =
            values["useAlternativeNames"]?.toBooleanStrict() ?: defaults.useAlternativeNames,
        useArrayPolymorphism = flag("useArrayPolymorphism", defaults.useArrayPolymorphism),
        allowStructuredMapKeys = flag("allowStructuredMapKeys", defaults.allowStructuredMapKeys),
        allowSpecialFloatingPointValues =
            flag("allowSpecialFloatingPointValues", defaults.allowSpecialFloatingPointValues),
        isLenient = flag("isLenient", defaults.isLenient),
        decodeEnumsCaseInsensitive = flag("decodeEnumsCaseInsensitive", defaults.decodeEnumsCaseInsensitive),
        allowTrailingComma = flag("allowTrailingComma", defaults.allowTrailingComma),
        allowComments = flag("allowComments", defaults.allowComments),
    )
}
