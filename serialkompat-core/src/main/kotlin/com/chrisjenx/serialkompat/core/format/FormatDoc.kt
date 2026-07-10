package com.chrisjenx.serialkompat.core.format

/**
 * The document AST for the canonical snapshot text — one immutable node tree
 * that BOTH directions flow through (`snapshotToDoc → render` on emit,
 * `readDoc → docToSnapshot` on parse), so grammar knowledge lives in one place.
 *
 * All string payloads are **unescaped** domain values: [FormatWriter] applies
 * escaping on render, and `FormatReader` unescapes on read.
 */
internal data class FormatDoc(
    val blocks: List<Block>,
)

/** One blank-line-separated block: a `@contract` or the `@config` block. */
internal data class Block(
    val lines: List<Line>,
)

/**
 * One text line: [indent] is the canonical indent level (0 = header, 1 = body,
 * 2 = subtype entry) — writer-side layout metadata, never derived from input.
 */
internal data class Line(
    val indent: Int,
    val tokens: List<Token>,
)

/** The five token shapes that cover the entire grammar. */
internal sealed interface Token {
    /** A bare word: markers (`@contract`, `subtypes:`), flags, a serial name. */
    data class Word(
        val text: String,
    ) : Token

    /** `key=value` — the key is a fixed grammar literal, never escaped. */
    data class KeyValue(
        val key: String,
        val value: String,
    ) : Token

    /** `key=[a,b,c]` — values are list-escaped on render. */
    data class KeyList(
        val key: String,
        val values: List<String>,
    ) : Token

    /** `name: type` — the element line head; renders as ONE space-joined token. */
    data class FieldRef(
        val name: String,
        val type: String,
    ) : Token

    /** `left -> right` — a subtype mapping line. */
    data class ArrowPair(
        val left: String,
        val right: String,
    ) : Token
}
