package com.chrisjenx.serialkompat.extractor.scanfixtures

import kotlinx.serialization.Serializable
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
