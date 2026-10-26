package io.github.jichuo1.meguviz.compat

import io.github.jichuo1.meguviz.ref.api.query.CompatibilityPolicy
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/** The recorded extension contract differs from BIL's boxed-isInstance method matcher. */
internal fun <T : Any> selectLegacyConstructor(
    owner: Class<T>,
    arguments: Array<out Any?>,
    publicOnly: Boolean = true
): Constructor<T>? {
    if (arguments.isNotEmpty() && arguments.all { it == null }) return null
    val choices = owner.declaredConstructors.mapNotNull { constructor ->
        if (publicOnly && !Modifier.isPublic(constructor.modifiers)) return@mapNotNull null
        if (constructor.parameterCount != arguments.size) return@mapNotNull null
        ConstructorChoice(constructor, constructor.parameterTypes)
    }
    val referenceChoices = choices.filter { candidate ->
        candidate.parameters.indices.all { index ->
            val parameter = candidate.parameters[index]
            !parameter.isPrimitive && (arguments[index] == null || parameter.isInstance(arguments[index]))
        }
    }
    val applicable = referenceChoices.ifEmpty {
        choices.filter { candidate ->
            candidate.parameters.indices.all { index ->
                CompatibilityPolicy.JAVA_INVOCATION.accepts(candidate.parameters[index], arguments[index]?.javaClass)
            }
        }
    }
    if (applicable.isEmpty()) return null
    val winner = applicable.filter { candidate ->
        applicable.all { other ->
            candidate === other || candidate.parameters.indices.all { index ->
                atLeastAsSpecific(candidate.parameters[index], other.parameters[index])
            }
        }
    }.singleOrNull() ?: throw IllegalArgumentException("Ambiguous constructor arguments for ${owner.name}")
    @Suppress("UNCHECKED_CAST")
    return winner.constructor as Constructor<T>
}

private class ConstructorChoice(val constructor: Constructor<*>, val parameters: Array<Class<*>>)

private fun atLeastAsSpecific(candidate: Class<*>, alternative: Class<*>): Boolean {
    if (candidate == alternative) return true
    if (candidate.isPrimitive) {
        val boxed = CompatibilityPolicy.boxed(candidate)
        return if (alternative.isPrimitive) CompatibilityPolicy.JAVA_INVOCATION.accepts(alternative, boxed)
        else alternative.isAssignableFrom(boxed)
    }
    return !alternative.isPrimitive && alternative.isAssignableFrom(candidate)
}
