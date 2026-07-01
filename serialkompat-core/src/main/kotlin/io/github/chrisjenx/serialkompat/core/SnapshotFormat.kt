package io.github.chrisjenx.serialkompat.core

/**
 * The canonical text codec for a [Snapshot] — the deterministic, human-readable,
 * diffable artifact (BCV's lesson: a sorted golden text reviewers can read).
 *
 * Guarantees:
 * - **Deterministic / byte-stable:** the same snapshot always serializes to the
 *   same bytes; everything is emitted in sorted order with no environment input.
 * - **Order-invariant:** because the [Snapshot] model normalizes its collections,
 *   reordering fields, enum values, or subtypes produces identical text.
 * - **Round-trips:** `parse(serialize(s)) == s` for any snapshot.
 *
 * Format (lists are comma-separated with no spaces; type refs contain no spaces):
 * ```
 * @contract com.example.OrderEvent kind=CLASS
 *   amountCents: Long
 *   note: String optional
 *   tags: List<String> optional jsonNames=[labels]
 *
 * @contract com.example.OrderStatus kind=ENUM
 *   values=[CANCELLED,CREATED,PAID]
 *
 * @contract com.example.Payment kind=SEALED discriminator=type
 *   subtypes:
 *     ach -> com.example.AchPayment
 *     card -> com.example.CardPayment
 *
 * @config
 *   classDiscriminator=type
 *   ...
 * ```
 */
public object SnapshotFormat {
    private const val INDENT = "  "
    private const val CONTRACT_PREFIX = "@contract "
    private const val CONFIG_HEADER = "@config"
    private const val SUBTYPE_ARROW = " -> "

    /** Serializes [snapshot] to its canonical text form. */
    public fun serialize(snapshot: Snapshot): String {
        val blocks = snapshot.contracts.map(::serializeContract) + serializeConfig(snapshot.config)
        return blocks.joinToString("\n\n")
    }

    /** Parses canonical text back into a [Snapshot]. Tolerant of blank lines. */
    public fun parse(text: String): Snapshot {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Snapshot()

        val contracts = mutableListOf<Contract>()
        var config = SnapshotConfig()
        for (block in trimmed.split(BLANK_LINE)) {
            val lines = block.lines()
            val header = lines.first().trim()
            when {
                header.startsWith(CONTRACT_PREFIX) -> contracts += parseContract(lines)
                header == CONFIG_HEADER -> config = parseConfig(lines)
                else -> error("serialkompat: unexpected snapshot block starting with '$header'")
            }
        }
        return Snapshot(contracts, config)
    }

    // --- serialize -------------------------------------------------------------

    private fun serializeContract(contract: Contract): String =
        buildString {
            append(CONTRACT_PREFIX).append(contract.serialName).append(" kind=").append(contract.kind.name)
            if (contract.discriminator != null) append(" discriminator=").append(contract.discriminator)
            when (contract.kind) {
                ContractKind.ENUM ->
                    appendLineItem("values=[" + contract.enumValues.joinToString(",") + "]")
                ContractKind.SEALED, ContractKind.POLYMORPHIC -> {
                    appendLineItem("subtypes:")
                    for (subtype in contract.subtypes) {
                        append('\n').append(INDENT).append(INDENT)
                        append(subtype.discriminatorValue).append(SUBTYPE_ARROW).append(subtype.serialName)
                    }
                }
                ContractKind.CLASS, ContractKind.OBJECT ->
                    for (element in contract.elements) appendLineItem(serializeElement(element))
            }
        }

    private fun StringBuilder.appendLineItem(item: String) {
        append('\n').append(INDENT).append(item)
    }

    private fun serializeElement(element: Element): String =
        buildString {
            append(element.name).append(": ").append(element.type)
            if (element.optional) append(" optional")
            if (element.nullable) append(" nullable")
            if (element.jsonNames.isNotEmpty()) {
                append(
                    " jsonNames=[",
                ).append(element.jsonNames.joinToString(",")).append("]")
            }
            if (element.encodeDefault != null) append(" encodeDefault=").append(element.encodeDefault.name)
        }

    private fun serializeConfig(config: SnapshotConfig): String =
        buildString {
            append(CONFIG_HEADER)
            // Emitted in a fixed (alphabetical) order for byte-stability.
            appendLineItem("classDiscriminator=" + config.classDiscriminator)
            appendLineItem("coerceInputValues=" + config.coerceInputValues)
            appendLineItem("encodeDefaults=" + config.encodeDefaults)
            appendLineItem("explicitNulls=" + config.explicitNulls)
            appendLineItem("ignoreUnknownKeys=" + config.ignoreUnknownKeys)
            appendLineItem("namingStrategy=" + config.namingStrategy)
        }

    // --- parse -----------------------------------------------------------------

    private fun parseContract(lines: List<String>): Contract {
        val headerTokens =
            lines
                .first()
                .trim()
                .removePrefix(CONTRACT_PREFIX)
                .trim()
                .split(" ")
        val serialName = headerTokens.first()
        var kind: ContractKind? = null
        var discriminator: String? = null
        for (token in headerTokens.drop(1)) {
            when {
                token.startsWith("kind=") -> kind = ContractKind.valueOf(token.removePrefix("kind="))
                token.startsWith("discriminator=") -> discriminator = token.removePrefix("discriminator=")
            }
        }
        requireNotNull(kind) { "serialkompat: contract '$serialName' is missing kind=" }

        val elements = mutableListOf<Element>()
        var enumValues = emptyList<String>()
        val subtypes = mutableListOf<Subtype>()
        for (raw in lines.drop(1)) {
            val body = raw.trim()
            when {
                body.isEmpty() || body == "subtypes:" -> Unit
                body.startsWith("values=[") -> enumValues = parseList(body.substringAfter("values="))
                SUBTYPE_ARROW in body -> {
                    val (value, name) = body.split(SUBTYPE_ARROW, limit = 2)
                    subtypes += Subtype(value.trim(), name.trim())
                }
                else -> elements += parseElement(body)
            }
        }
        return Contract(serialName, kind, elements, enumValues, discriminator, subtypes)
    }

    private fun parseElement(body: String): Element {
        val name = body.substringBefore(": ")
        val tokens = body.substringAfter(": ").trim().split(" ")
        var optional = false
        var nullable = false
        var jsonNames = emptyList<String>()
        var encodeDefault: EncodeDefaultMode? = null
        for (token in tokens.drop(1)) {
            when {
                token == "optional" -> optional = true
                token == "nullable" -> nullable = true
                token.startsWith("jsonNames=") -> jsonNames = parseList(token.removePrefix("jsonNames="))
                token.startsWith("encodeDefault=") ->
                    encodeDefault = EncodeDefaultMode.valueOf(token.removePrefix("encodeDefault="))
            }
        }
        return Element(name, tokens.first(), optional, nullable, jsonNames, encodeDefault)
    }

    private fun parseConfig(lines: List<String>): SnapshotConfig {
        val values =
            lines
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
        val defaults = SnapshotConfig()
        return SnapshotConfig(
            namingStrategy = values["namingStrategy"] ?: defaults.namingStrategy,
            classDiscriminator = values["classDiscriminator"] ?: defaults.classDiscriminator,
            ignoreUnknownKeys = values["ignoreUnknownKeys"]?.toBooleanStrict() ?: defaults.ignoreUnknownKeys,
            encodeDefaults = values["encodeDefaults"]?.toBooleanStrict() ?: defaults.encodeDefaults,
            explicitNulls = values["explicitNulls"]?.toBooleanStrict() ?: defaults.explicitNulls,
            coerceInputValues = values["coerceInputValues"]?.toBooleanStrict() ?: defaults.coerceInputValues,
        )
    }

    /** Parses a `[a,b,c]` list literal; an empty `[]` yields an empty list. */
    private fun parseList(literal: String): List<String> =
        literal
            .trim()
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val BLANK_LINE = Regex("\\n[ \\t]*\\n")
}
