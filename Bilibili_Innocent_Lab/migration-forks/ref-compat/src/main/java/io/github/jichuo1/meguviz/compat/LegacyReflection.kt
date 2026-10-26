@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")

package io.github.jichuo1.meguviz.compat

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec
import io.github.jichuo1.meguviz.ref.api.query.Selection
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

/** Source migration adapter for the recorded Hikage/BetterAndroid surface only. */
class LegacyResolver<T : Any>(private val owner: Class<T>, private val receiver: Any? = null) {
    private var customProcessor: MemberProcessor.Resolver? = null
    fun optional(silent: Boolean = false) = this
    fun processor(value: MemberProcessor.Resolver) = apply { customProcessor = value }

    private fun members(owner: Class<*>, kind: MemberKind, condition: MemberCondition): List<Member> {
        val processor = customProcessor
        if (processor != null) {
            // Existing Hikage owns this explicit platform adapter and its hidden-API policy.
            val owners = listOf(owner)
            return owners.flatMap { type ->
                when (kind) {
                    MemberKind.METHOD -> processor.getDeclaredMethods(type)
                    MemberKind.FIELD -> processor.getDeclaredFields(type)
                    MemberKind.CONSTRUCTOR -> processor.getDeclaredConstructors(type)
                }
            }.filter(condition::matches)
        }
        return CodeScope.open(owner.classLoader, AccessPolicy.PRESERVE_JAVA_ACCESS, 16384).use { scope ->
            val builder = QuerySpec.builder(owner, kind).selection(Selection.ALL)
            condition.name?.let(builder::name)
            condition.types?.let { builder.parameters(*it) }
            condition.parameterCount?.let(builder::parameterCount)
            val result = scope.resolve(builder.build())
            if (!result.receipt().coverage().complete()) throw IncompleteLookupException("Incomplete legacy lookup: ${result.status()}")
            result.members().map { scope.exportRaw(it) }.filter(condition::matches)
        }
    }

    private fun first(kind: MemberKind, condition: MemberCondition): Member? {
        val owners = if (condition.inherited && kind != MemberKind.CONSTRUCTOR) generateSequence(owner as Class<*>) { it.superclass } else sequenceOf(owner)
        for (type in owners) members(type, kind, condition).firstOrNull()?.let { return it }
        return null
    }

    fun firstMethodOrNull(block: MemberCondition.() -> Unit): MethodResolver<T>? = lookup {
        first(MemberKind.METHOD, MemberCondition().apply(block))?.let { MethodResolver<T>(it as Method, receiver) }
    }
    fun firstFieldOrNull(block: MemberCondition.() -> Unit): FieldResolver<T>? = lookup {
        first(MemberKind.FIELD, MemberCondition().apply(block))?.let { FieldResolver<T>(it as Field, receiver) }
    }
    fun firstConstructorOrNull(block: MemberCondition.() -> Unit): ConstructorResolver<T>? = lookup {
        first(MemberKind.CONSTRUCTOR, MemberCondition().apply(block))?.let { ConstructorResolver(it as Constructor<T>) }
    }
}

class MemberCondition {
    var name: String? = null
    var parameterCount: Int? = null
    internal var inherited = false
    internal var types: Array<out Class<*>>? = null
    private var flags = 0
    private var parameterPredicate: ((Array<Class<*>>) -> Boolean)? = null
    fun superclass() { inherited = true }
    fun emptyParameters() { types = emptyArray(); parameterCount = 0 }
    fun parameters(vararg values: KClass<*>) { types = values.map { it.java }.toTypedArray(); parameterCount = values.size }
    fun parameters(block: (Array<Class<*>>) -> Boolean) { parameterPredicate = block }
    fun modifiers(vararg values: Int) { flags = values.fold(0) { a, b -> a or b } }
    internal fun matches(member: Member): Boolean {
        if (name != null && name != member.name || member.modifiers and flags != flags) return false
        val parameters = when (member) { is Method -> member.parameterTypes; is Constructor<*> -> member.parameterTypes; else -> emptyArray() }
        if (parameterCount != null && parameterCount != parameters.size) return false
        if (types != null && !types!!.contentEquals(parameters)) return false
        return parameterPredicate?.invoke(parameters) ?: true
    }
}

object Modifiers { const val STATIC = Modifier.STATIC; const val NATIVE = Modifier.NATIVE }

object MemberProcessor {
    open class Resolver {
        open fun <T : Any> getDeclaredMethods(declaringClass: Class<T>): List<Method> = declaringClass.declaredMethods.toList()
        open fun <T : Any> getDeclaredFields(declaringClass: Class<T>): List<Field> = declaringClass.declaredFields.toList()
        open fun <T : Any> getDeclaredConstructors(declaringClass: Class<T>): List<Constructor<T>> = declaringClass.declaredConstructors.map { it as Constructor<T> }
    }
}

private fun accessible(member: Member) {
    @Suppress("DEPRECATION")
    (member as AccessibleObject).isAccessible = true
}

class MethodResolver<T : Any>(val self: Method, private var receiver: Any? = null) {
    fun copy() = MethodResolver<T>(self)
    fun of(value: Any?) = apply { receiver = value }
    fun invokeQuietly(vararg arguments: Any?): Any? = lookup { accessible(self); self.invoke(receiver, *arguments) }
    @JvmName("invokeTypedQuietly")
    fun <R> invokeQuietly(vararg arguments: Any?): R? = invokeQuietly(*arguments) as R?
    fun invoke(vararg arguments: Any?): Any? { accessible(self); return self.invoke(receiver, *arguments) }
    @JvmName("invokeTyped")
    fun <R> invoke(vararg arguments: Any?): R? = invoke(*arguments) as R?
}

class FieldResolver<T : Any>(val self: Field, private var receiver: Any? = null) {
    fun copy() = FieldResolver<T>(self)
    fun of(value: Any?) = apply { receiver = value }
    fun getQuietly(): Any? = lookup { accessible(self); self.get(receiver) }
    @JvmName("getTypedQuietly")
    fun <R> getQuietly(): R? = getQuietly() as R?
    fun setQuietly(value: Any?) { lookup { accessible(self); self.set(receiver, value) } }
}

class ConstructorResolver<T : Any>(val self: Constructor<T>) {
    fun create(vararg arguments: Any?): T { accessible(self); return self.newInstance(*arguments) }
    fun createQuietly(vararg arguments: Any?): T? = lookup { create(*arguments) }
}

class IncompleteLookupException(message: String) : IllegalStateException(message)
// Quiet variants also absorb linkage failures (missing/broken classes, failed static init), as the legacy contract did.
private inline fun <T> lookup(block: () -> T): T? = try { block() } catch (_: ReflectiveOperationException) { null } catch (_: LinkageError) { null } catch (failure: RuntimeException) {
    if (failure is IncompleteLookupException || failure is java.util.concurrent.CancellationException) throw failure
    null
}

fun <T : Any> Class<T>.resolve() = LegacyResolver(this)
fun <T : Any> KClass<T>.resolve() = java.resolve()
fun <T : Any> T.asResolver() = LegacyResolver(javaClass, this)
inline fun <reified T : Any> classOf(primitiveType: Boolean = true): Class<T> =
    if (primitiveType) T::class.javaPrimitiveType ?: T::class.java else T::class.javaObjectType
infix fun Class<*>.isSubclassOf(superclass: Class<*>) = superclass.isAssignableFrom(this)
infix fun Class<*>.isSubclassOf(superclass: KClass<*>) = superclass.java.isAssignableFrom(this)
infix fun KClass<*>.isSubclassOf(superclass: KClass<*>) = superclass.java.isAssignableFrom(java)
infix fun KClass<*>.isNotSubclassOf(superclass: KClass<*>) = !superclass.java.isAssignableFrom(java)
infix fun Class<*>.isNotSubclassOf(superclass: Class<*>) = !superclass.isAssignableFrom(this)
fun ClassLoader.hasClass(name: String) = lookup { loadClass(name) } != null
fun String.toClass(loader: ClassLoader? = null, initialize: Boolean = false): Class<Any> =
    Class.forName(this, initialize, loader ?: LegacyResolver::class.java.classLoader) as Class<Any>
fun String.toClassOrNull(loader: ClassLoader? = null, initialize: Boolean = false): Class<Any>? = lookup { toClass(loader, initialize) }
fun lazyClass(name: String): Lazy<Class<Any>> = lazy { name.toClass() }
fun lazyClassOrNull(name: String): Lazy<Class<Any>?> = lazy { name.toClassOrNull() }
fun Class<*>.toClass(): Class<Any> = this as Class<Any>
@JvmName("typedClass")
fun <T : Any> Class<*>.toClass(): Class<T> = this as Class<T>
fun Type.toClass(): Class<Any> = when (this) {
    is Class<*> -> this as Class<Any>
    is ParameterizedType -> rawType.toClass()
    is GenericArrayType -> java.lang.reflect.Array.newInstance(genericComponentType.toClass(), 0).javaClass as Class<Any>
    else -> throw ClassCastException("Unsupported generic type")
}
fun Type.toClassOrNull(): Class<Any>? = lookup { toClass() }
fun Class<*>.genericSuperclassTypeArguments(): Array<Type> = try {
    lookup { (genericSuperclass as? ParameterizedType)?.actualTypeArguments } ?: emptyArray()
} catch (_: java.lang.reflect.GenericSignatureFormatError) {
    emptyArray()
}
fun <T : Any> KClass<T>.createInstanceOrNull(vararg arguments: Any?): T? = java.createInstanceOrNull(*arguments)
fun <T : Any> Class<T>.createInstanceOrNull(vararg arguments: Any?): T? = lookup {
    val constructor = selectLegacyConstructor(this, arguments) ?: return@lookup null
    accessible(constructor)
    constructor.newInstance(*arguments) as T
}
