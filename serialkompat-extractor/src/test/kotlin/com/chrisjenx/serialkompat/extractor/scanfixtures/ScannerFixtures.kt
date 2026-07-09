package com.chrisjenx.serialkompat.extractor.scanfixtures

import com.chrisjenx.serialkompat.annotations.SerialkompatChecked
import com.chrisjenx.serialkompat.annotations.SerialkompatIgnore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File

@Serializable
data class ScannedOrder(
    val id: String,
)

class NotSerializable(
    val id: String,
)

class Outer {
    @Serializable
    data class Inner(
        val id: String,
    )
}

@Serializable
object ScannedMarker

@Serializable
enum class ScannedStatus { NEW, DONE }

@Serializable
sealed interface ScannedEvent {
    @Serializable
    data class Created(
        val id: String,
    ) : ScannedEvent
}

@Serializable
data class ScannedBox<T>(
    val value: T,
)

object IntAsStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntAsString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Int = decoder.decodeString().toInt()
}

/**
 * Carries only a *property-level* `@Serializable(with = …)`: the constant pool
 * contains the annotation descriptor string, but there is no class-level
 * annotation — the scanner must not mark this class (false-positive guard).
 */
class PropertyLevelOnly(
    @Serializable(with = IntAsStringSerializer::class) val count: Int,
)

@Serializable
data class ScannedWithGenericSupertype(
    val id: String,
) : Comparable<ScannedWithGenericSupertype> {
    override fun compareTo(other: ScannedWithGenericSupertype): Int = id.compareTo(other.id)
}

@Serializable
@SerialkompatIgnore
data class ScannedIgnored(
    val id: String,
)

@Serializable
@SerialkompatChecked
data class ScannedOptedIn(
    val id: String,
)

/** `@SerialkompatChecked` without `@Serializable`: never a candidate, so never opted in. */
@SerialkompatChecked
class CheckedButNotSerializable(
    val id: String,
)

@Serializable
@SerialkompatIgnore
data class ScannedIgnoredBox<T>(
    val value: T,
)

/**
 * Copies this package's compiled class files into [tempRoot] so a scan sees a
 * deterministic set: exactly the fixtures declared in this package.
 */
fun scanFixturesRoot(tempRoot: File): File {
    val classesRoot =
        File(
            ScannedOrder::class.java.protectionDomain.codeSource.location
                .toURI(),
        )
    val pkg = "com/chrisjenx/serialkompat/extractor/scanfixtures"
    check(File(classesRoot, pkg).isDirectory) { "expected compiled fixtures under $classesRoot" }
    File(classesRoot, pkg).copyRecursively(File(tempRoot, pkg))
    return tempRoot
}
