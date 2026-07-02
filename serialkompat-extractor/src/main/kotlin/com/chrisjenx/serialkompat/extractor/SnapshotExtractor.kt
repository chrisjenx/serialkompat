package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Builds a [Snapshot] from compiled `@Serializable` [SerialDescriptor]s — the
 * anti-corruption boundary between kotlinx-serialization and the pure engine.
 * The rest of the tool depends only on [Snapshot], so a future compiler-plugin
 * extractor (Approach C) can drop in behind this interface without touching the
 * differ, classifier, or rules (design §3–§4).
 */
public interface SnapshotExtractor {
    /**
     * Walks the [roots] descriptor graphs into a [Snapshot].
     *
     * @param roots the in-scope `@Serializable` types' descriptors.
     * @param module resolves open-polymorphic subtypes (from the user's `Json`).
     * @param config the wire config recorded on the resulting snapshot; also
     *   supplies the default polymorphic discriminator key.
     */
    public fun extract(
        roots: Iterable<SerialDescriptor>,
        module: SerializersModule = EmptySerializersModule(),
        config: SnapshotConfig = SnapshotConfig(),
    ): Snapshot
}
