// --8<-- [start:imports]
import com.chrisjenx.serialkompat.core.CompatibilityDirection
// --8<-- [end:imports]

// --8<-- [start:config]
serialkompat {
    types.set(listOf( // (1)!
        "com.example.wire.OrderEvent",
        "com.example.wire.Payment",
    ))
    jsonInstance.set("com.example.wire.WireJson.instance") // (2)!
    baselineRef.set("origin/main") // (3)!
    direction.set(CompatibilityDirection.FULL) // (4)!
    failOnBreaking.set(true) // (5)!
    failOnEmptyBaseline.set(true) // (6)!
    include.set(listOf("com.example.wire")) // (7)!
    exclude.set(listOf("com.example.wire.internal")) // (8)!
    renames.put("com.example.wire.LegacyOrder", "com.example.wire.OrderEvent") // (9)!
    acceptedBreaks.set(listOf( // (10)!
        "com.example.wire.Payment PROPERTY_REMOVED BACKWARD",
    ))
}
// --8<-- [end:config]
