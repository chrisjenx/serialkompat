package com.chrisjenx.serialkompat.extractor

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Discovers `@Serializable` types by parsing compiled class files directly — no
 * classloading, no dependencies (design §4, issue #55). `@Serializable` has RUNTIME
 * retention, so an annotated class carries `Lkotlinx/serialization/Serializable;` in
 * its class-level `RuntimeVisibleAnnotations` attribute. Only the *class-level*
 * attribute is matched: a property-level `@Serializable(with = …)` puts the same
 * string in the constant pool but must not mark the class.
 */
internal object SerializableClassScanner {
    private const val CLASS_MAGIC = -0x35014542 // 0xCAFEBABE
    private const val SERIALIZABLE = "Lkotlinx/serialization/Serializable;"

    internal data class ScanResult(
        /** Binary names (`com.foo.Bar$Baz`) of classes with a class-level `@Serializable`. */
        val typeNames: List<String>,
        /** Root-relative paths of class files that could not be parsed (→ OPAQUE upstream). */
        val unreadable: List<String>,
        /** Binary names of `@Serializable` classes skipped because they declare type parameters. */
        val skippedGenerics: List<String>,
    )

    /** Scans every `*.class` under each of [roots]; results are sorted + deduped. Never throws. */
    fun scan(roots: List<File>): ScanResult {
        val typeNames = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val skippedGenerics = mutableListOf<String>()
        for (root in roots) {
            val classFiles =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .sortedBy(File::getPath)
            for (file in classFiles) {
                val parsed = runCatching { parse(file.readBytes()) }.getOrNull()
                when {
                    parsed == null -> unreadable += file.relativeTo(root).path
                    !parsed.serializable -> {}
                    parsed.generic -> skippedGenerics += parsed.binaryName
                    else -> typeNames += parsed.binaryName
                }
            }
        }
        return ScanResult(
            typeNames = typeNames.distinct().sorted(),
            unreadable = unreadable.sorted(),
            skippedGenerics = skippedGenerics.distinct().sorted(),
        )
    }

    private class ParsedClass(
        val binaryName: String,
        val serializable: Boolean,
        val generic: Boolean,
    )

    private fun parse(bytes: ByteArray): ParsedClass {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != CLASS_MAGIC) throw IOException("not a class file")
        input.skipNBytes(4) // minor + major version
        val utf8 = HashMap<Int, String>()
        val classNameIndex = HashMap<Int, Int>()
        val constantPoolCount = input.readUnsignedShort()
        var slot = 1
        while (slot < constantPoolCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[slot] = input.readUTF()
                7 -> classNameIndex[slot] = input.readUnsignedShort() // Class → name_index
                8, 16, 19, 20 -> input.skipNBytes(2) // String, MethodType, Module, Package
                15 -> input.skipNBytes(3) // MethodHandle
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                5, 6 -> { // Long and Double occupy two constant pool slots
                    input.skipNBytes(8)
                    slot++
                }
                else -> throw IOException("unknown constant pool tag $tag")
            }
            slot++
        }
        input.skipNBytes(2) // access_flags
        val thisClass = input.readUnsignedShort()
        val binaryName = utf8.getValue(classNameIndex.getValue(thisClass)).replace('/', '.')
        input.skipNBytes(2) // super_class
        input.skipNBytes(input.readUnsignedShort() * 2L) // interfaces
        skipMembers(input) // fields
        skipMembers(input) // methods
        var serializable = false
        var generic = false
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt().toLong() and 0xFFFFFFFFL
            when (name) {
                "RuntimeVisibleAnnotations" -> serializable = readAnnotations(input, utf8) || serializable
                // A class signature starting `<` declares type parameters (JVMS §4.7.9.1).
                "Signature" -> generic = utf8[input.readUnsignedShort()]?.startsWith("<") == true
                else -> input.skipNBytes(length)
            }
        }
        return ParsedClass(binaryName, serializable, generic)
    }

    private fun skipMembers(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.skipNBytes(6) // access_flags + name_index + descriptor_index
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(2) // attribute_name_index
                input.skipNBytes(input.readInt().toLong() and 0xFFFFFFFFL)
            }
        }
    }

    private fun readAnnotations(
        input: DataInputStream,
        utf8: Map<Int, String>,
    ): Boolean {
        var found = false
        repeat(input.readUnsignedShort()) {
            if (utf8[input.readUnsignedShort()] == SERIALIZABLE) found = true
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(2) // element_name_index
                skipElementValue(input)
            }
        }
        return found
    }

    private fun skipElementValue(input: DataInputStream) {
        when (val tag = input.readUnsignedByte().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.skipNBytes(2)
            'e' -> input.skipNBytes(4) // enum: type_name_index + const_name_index
            '@' -> { // nested annotation: type_index + element_value_pairs
                input.skipNBytes(2)
                repeat(input.readUnsignedShort()) {
                    input.skipNBytes(2)
                    skipElementValue(input)
                }
            }
            '[' -> repeat(input.readUnsignedShort()) { skipElementValue(input) }
            else -> throw IOException("unknown element_value tag '$tag'")
        }
    }
}
