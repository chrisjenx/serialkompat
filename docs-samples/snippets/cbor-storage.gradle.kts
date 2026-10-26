import com.chrisjenx.serialkompat.core.WireFormat

serialkompat {
    format.set(WireFormat.CBOR)
    cborInstance.set("com.example.storage.CacheCbor.instance")
    types.set(listOf("com.example.api.OrderResponse", "com.example.api.UserResponse"))
}
