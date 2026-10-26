package com.example

import com.chrisjenx.serialkompat.annotations.SerialkompatIgnore
import kotlinx.serialization.Serializable

// Embedded on docs/adopting.md ("Flip to opt-out").
// --8<-- [start:debug-dump]
@Serializable
@SerialkompatIgnore // internal scratch type, no cross-version contract
data class DebugDump(
    val raw: String,
)
// --8<-- [end:debug-dump]
