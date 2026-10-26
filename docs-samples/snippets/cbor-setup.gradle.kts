import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.WireFormat

serialkompat {
    format.set(WireFormat.CBOR)
    cborInstance.set("com.example.storage.WireCbor.instance") // unset = Cbor.Default
    types.set(listOf("com.example.storage.CacheEntry"))
    direction.set(CompatibilityDirection.FULL)
}
