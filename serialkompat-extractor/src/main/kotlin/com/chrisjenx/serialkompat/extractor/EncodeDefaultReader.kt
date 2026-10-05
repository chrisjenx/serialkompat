package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.EncodeDefaultMode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Recovers each optional element's `@EncodeDefault` mode (#158).
 *
 * `@EncodeDefault` is not a `@SerialInfo` annotation, so the descriptor never exposes it. It is,
 * however, RUNTIME-retained: Kotlin stores property-targeted annotations (it and `@SerialName`) on
 * the property's synthetic static `get<Name>$annotations()` / `is<Name>$annotations()` method. The
 * owning class is reached through the plugin-generated serializer behind the descriptor (a
 * `$serializer` nested in the model class).
 *
 * Soundness: a mode — including [EncodeDefaultMode.ABSENT] — is reported only on positive evidence
 * (owner resolved, property matched by `@SerialName` or by a backing field). Anything unresolved —
 * a hand-written serializer, an unmatched element, any reflection failure — yields `null` (unknown),
 * which the classifier never treats as ABSENT. This never throws (design §10).
 */
@OptIn(ExperimentalSerializationApi::class)
internal object EncodeDefaultReader {
    /** Element index → mode for [descriptor]'s optional elements; an index absent from the map is unknown. */
    fun modes(descriptor: SerialDescriptor): Map<Int, EncodeDefaultMode> =
        runCatching { readModes(descriptor) }.getOrDefault(emptyMap())

    private fun readModes(descriptor: SerialDescriptor): Map<Int, EncodeDefaultMode> {
        val owner = ownerClass(descriptor) ?: return emptyMap()
        val hierarchy = generateSequence(owner) { it.superclass }.takeWhile { it != Any::class.java }.toList()
        val annotationHolders = hierarchy.flatMap { cls -> cls.declaredMethods.filter(::isAnnotationHolder) }
        val bySerialName = annotationHolders.associateByFirst { it.getAnnotation(SerialName::class.java)?.value }
        val byMethodName =
            annotationHolders
                .filter { it.getAnnotation(SerialName::class.java) == null }
                .associateByFirst { it.name }
        val fieldNames = hierarchy.flatMap { cls -> cls.declaredFields.map { it.name } }.toSet()

        val matched = mutableSetOf<Method>()
        val holderModes = mutableMapOf<Int, EncodeDefaultMode>()
        val fieldOnly = mutableListOf<Int>()
        for (i in 0 until descriptor.elementsCount) {
            if (!descriptor.isElementOptional(i)) continue
            val name = descriptor.getElementName(i)
            val holder = bySerialName[name] ?: byMethodName[annotationHolderName(name)]
            when {
                holder != null -> {
                    matched += holder
                    holderModes[i] = holder.getAnnotation(EncodeDefault::class.java).toMode()
                }

                // No annotation holder at all: the property carries no annotations, so no
                // @EncodeDefault — but only if it provably exists under this name.
                name in fieldNames -> {
                    fieldOnly += i
                }
            }
        }
        // An @EncodeDefault holder no element claimed (e.g. renamed by @get:JvmName) could belong to
        // any field-only match, so none of those can be proven ABSENT — they stay unknown.
        val orphaned = annotationHolders.any { it !in matched && it.isAnnotationPresent(EncodeDefault::class.java) }
        return if (orphaned) holderModes else holderModes + fieldOnly.associateWith { EncodeDefaultMode.ABSENT }
    }

    /** The model class behind a plugin-generated descriptor, or null for anything else. */
    private fun ownerClass(descriptor: SerialDescriptor): Class<*>? {
        val unwrapped = generateSequence(descriptor) { it.privateField("original") as? SerialDescriptor }.last()
        val serializer = unwrapped.privateField("generatedSerializer") ?: return null
        // Guard against a foreign serializer: it must be the one that produced this descriptor.
        val ownDescriptor = serializer.javaClass.getMethod("getDescriptor").invoke(serializer)
        if (ownDescriptor !== unwrapped) return null
        return serializer.javaClass.enclosingClass
    }

    private fun Any.privateField(name: String): Any? {
        val field =
            generateSequence<Class<*>>(javaClass) { it.superclass }
                .firstNotNullOfOrNull { cls -> cls.declaredFields.firstOrNull { it.name == name } }
                ?: return null
        field.isAccessible = true
        return field.get(this)
    }

    private fun isAnnotationHolder(method: Method): Boolean =
        method.isSynthetic &&
            Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.name.endsWith(ANNOTATIONS_SUFFIX)

    /** Kotlin's JVM getter naming: `isFoo` keeps its name, anything else becomes `getFoo`. */
    private fun annotationHolderName(property: String): String {
        val isPrefixed = property.length > 2 && property.startsWith("is") && !property[2].isLowerCase()
        val getter = if (isPrefixed) property else "get" + property.replaceFirstChar { it.uppercaseChar() }
        return getter + ANNOTATIONS_SUFFIX
    }

    private fun EncodeDefault?.toMode(): EncodeDefaultMode =
        when (this?.mode) {
            null -> EncodeDefaultMode.ABSENT
            EncodeDefault.Mode.ALWAYS -> EncodeDefaultMode.ALWAYS
            EncodeDefault.Mode.NEVER -> EncodeDefaultMode.NEVER
        }

    /** Like associateBy, but the first (most-derived) entry wins and null keys are dropped. */
    private fun <K : Any> List<Method>.associateByFirst(key: (Method) -> K?): Map<K, Method> =
        buildMap { this@associateByFirst.forEach { m -> key(m)?.let { putIfAbsent(it, m) } } }

    private const val ANNOTATIONS_SUFFIX = "\$annotations"
}
