package com.chrisjenx.serialkompat.extractor.basefixtures

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

// A dogfood-shaped model (#200): several sealed bases reuse the same subtype serial names
// (`board`, `equine`, `unknown`) with different shapes. Subtype names are scoped per base, so this is
// legal kotlinx-serialization, and class-dir discovery hands every one of these classes in as a root.

@Serializable
@SerialName("Stall")
sealed interface Stall {
    @Serializable
    @SerialName("board")
    data class Board(
        val stallId: String,
        val rateCents: Int,
    ) : Stall

    @Serializable
    @SerialName("unknown")
    data object Unknown : Stall
}

@Serializable
@SerialName("Horse")
sealed interface Horse {
    @Serializable
    @SerialName("board")
    data class Board(
        val horseId: String,
    ) : Horse

    @Serializable
    @SerialName("equine")
    data class Equine(
        val breed: String,
    ) : Horse

    @Serializable
    @SerialName("unknown")
    data class Unknown(
        val raw: String? = null,
    ) : Horse
}

@Serializable
@SerialName("Invoice")
sealed interface Invoice {
    @Serializable
    @SerialName("board")
    data class Board(
        val amountCents: Long,
    ) : Invoice

    @Serializable
    @SerialName("equine")
    data class Equine(
        val vet: String,
    ) : Invoice

    @Serializable
    @SerialName("unknown")
    data object Unknown : Invoice
}

/** Copies this package's compiled classes into [tempRoot] so it can be handed to the class-dir scan. */
fun baseFixturesRoot(tempRoot: File): File {
    val classesRoot =
        File(
            Stall::class.java.protectionDomain.codeSource.location
                .toURI(),
        )
    val pkg = "com/chrisjenx/serialkompat/extractor/basefixtures"
    check(File(classesRoot, pkg).isDirectory) { "expected compiled fixtures under $classesRoot" }
    File(classesRoot, pkg).copyRecursively(File(tempRoot, pkg))
    return tempRoot
}
