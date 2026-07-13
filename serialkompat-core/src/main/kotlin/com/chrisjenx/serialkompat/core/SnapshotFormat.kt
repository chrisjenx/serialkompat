package com.chrisjenx.serialkompat.core

import com.chrisjenx.serialkompat.core.format.FormatReader
import com.chrisjenx.serialkompat.core.format.FormatWriter
import com.chrisjenx.serialkompat.core.format.docToSnapshot
import com.chrisjenx.serialkompat.core.format.snapshotToDoc

/**
 * The canonical text codec for a [Snapshot] — the deterministic, human-readable,
 * diffable artifact (BCV's lesson: a sorted golden text reviewers can read).
 *
 * Guarantees:
 * - **Deterministic / byte-stable:** the same snapshot always serializes to the
 *   same bytes; everything is emitted in sorted order with no environment input.
 * - **Order-invariant:** because the [Snapshot] model normalizes its collections,
 *   reordering fields, enum values, or subtypes produces identical text.
 * - **Round-trips:** `parse(serialize(s)) == s` for every extractor-produced
 *   snapshot; list values (jsonNames, enum values) escape whitespace and the
 *   comma separator, so they survive tokenization like any other token (#146).
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
    /** Serializes [snapshot] to its canonical text form. */
    public fun serialize(snapshot: Snapshot): String = FormatWriter.render(snapshotToDoc(snapshot))

    /** Parses canonical text back into a [Snapshot]. Tolerant of blank lines. */
    public fun parse(text: String): Snapshot = docToSnapshot(FormatReader.readDoc(text))
}
