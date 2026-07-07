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
    // `0xCAFEBABE` as a 32-bit int (it overflows a positive Int, hence `.toInt()`).
    private val CLASS_MAGIC = 0xCAFEBABE.toInt()
    private const val SERIALIZABLE = "Lkotlinx/serialization/Serializable;"

    // constant_pool entry tags (JVMS §4.4, Table 4.4-B). The format is additive-only:
    // these have never changed size/meaning; an unknown tag → the file is unreadable.
    private const val CP_UTF8 = 1
    private const val CP_INTEGER = 3
    private const val CP_FLOAT = 4
    private const val CP_LONG = 5
    private const val CP_DOUBLE = 6
    private const val CP_CLASS = 7
    private const val CP_STRING = 8
    private const val CP_FIELDREF = 9
    private const val CP_METHODREF = 10
    private const val CP_INTERFACE_METHODREF = 11
    private const val CP_NAME_AND_TYPE = 12
    private const val CP_METHOD_HANDLE = 15
    private const val CP_METHOD_TYPE = 16
    private const val CP_DYNAMIC = 17
    private const val CP_INVOKE_DYNAMIC = 18
    private const val CP_MODULE = 19
    private const val CP_PACKAGE = 20

    // element_value tags (JVMS §4.7.16.1). The single-index primitives/String/class all
    // carry one 2-byte constant_pool index; enum/annotation/array need structural handling.
    private const val EV_ENUM = 'e'
    private const val EV_ANNOTATION = '@'
    private const val EV_ARRAY = '['

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
            unreadable = unreadable.distinct().sorted(),
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
                CP_UTF8 -> utf8[slot] = input.readUTF()
                CP_CLASS -> classNameIndex[slot] = input.readUnsignedShort() // → name_index
                CP_STRING, CP_METHOD_TYPE, CP_MODULE, CP_PACKAGE -> input.skipNBytes(2)
                CP_METHOD_HANDLE -> input.skipNBytes(3)
                CP_INTEGER, CP_FLOAT, CP_FIELDREF, CP_METHODREF,
                CP_INTERFACE_METHODREF, CP_NAME_AND_TYPE, CP_DYNAMIC, CP_INVOKE_DYNAMIC,
                -> input.skipNBytes(4)
                CP_LONG, CP_DOUBLE -> {
                    input.skipNBytes(8)
                    slot++ // Long/Double occupy two constant_pool slots (JVMS §4.4.5)
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
            // Single 2-byte constant_pool index: primitives B/C/D/F/I/J/S/Z, String s, class c.
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.skipNBytes(2)
            EV_ENUM -> input.skipNBytes(4) // type_name_index + const_name_index
            EV_ANNOTATION -> { // nested annotation: type_index + element_value_pairs
                input.skipNBytes(2)
                repeat(input.readUnsignedShort()) {
                    input.skipNBytes(2)
                    skipElementValue(input)
                }
            }
            EV_ARRAY -> repeat(input.readUnsignedShort()) { skipElementValue(input) }
            else -> throw IOException("unknown element_value tag '$tag'")
        }
    }
}
