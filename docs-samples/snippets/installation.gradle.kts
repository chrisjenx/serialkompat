// --8<-- [start:imports]
import com.chrisjenx.serialkompat.core.CompatibilityDirection
// --8<-- [end:imports]

// --8<-- [start:config]
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent", "com.example.wire.Payment"))
    jsonInstance.set("com.example.wire.WireJson.instance")
    // baselineRef is optional. Unset, it auto-detects your default branch.
    direction.set(CompatibilityDirection.FULL)
    failOnBreaking.set(true)
    failOnEmptyBaseline.set(true)
}
// --8<-- [end:config]
