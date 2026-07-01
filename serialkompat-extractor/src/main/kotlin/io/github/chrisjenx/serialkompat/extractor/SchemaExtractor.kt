package io.github.chrisjenx.serialkompat.extractor

import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Extracts wire-relevant information from kotlinx-serialization [SerialDescriptor]s.
 *
 * Implementations walk the descriptor graph and produce the canonical snapshot the
 * diff/classify engine consumes (design doc, sections 3-4). This interface starts
 * minimal and grows as the extractor is built out.
 */
public interface SchemaExtractor {
    /** Returns the element (JSON key) names declared directly on [descriptor], in declaration order. */
    public fun elementNames(descriptor: SerialDescriptor): List<String>
}

/**
 * A minimal reference extractor that reads element names straight off a descriptor.
 *
 * Proves the runtime-descriptor pipeline end to end; richer extraction (nullability,
 * optionality, kinds, polymorphism, config) is layered on in later work.
 */
public object DirectSchemaExtractor : SchemaExtractor {
    override fun elementNames(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount).map(descriptor::getElementName)
}
