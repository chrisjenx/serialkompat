// The page shows this block after cbor-setup's imports, so it embeds only the config region.
import com.chrisjenx.serialkompat.core.WireFormat

// --8<-- [start:config]
serialkompat {
    format.set(WireFormat.CBOR)
    cborInstance.set("com.example.storage.CacheCbor.instance")
    types.set(listOf("com.example.api.OrderResponse", "com.example.api.UserResponse"))
}
// --8<-- [end:config]
