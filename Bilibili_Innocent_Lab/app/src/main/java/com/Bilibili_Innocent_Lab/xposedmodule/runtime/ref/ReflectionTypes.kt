package com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/** BIL 的类型兼容入口；不模拟第三方二进制命名空间。 */
internal inline fun <reified T : Any> classOf(primitiveType: Boolean = true): Class<T> =
    if (primitiveType) T::class.javaPrimitiveType ?: T::class.java else T::class.javaObjectType

internal val Member.isStatic get() = Modifier.isStatic(modifiers)
internal val Member.isAbstract get() = Modifier.isAbstract(modifiers)
internal val Member.isFinal get() = Modifier.isFinal(modifiers)
internal val Member.isPublic get() = Modifier.isPublic(modifiers)
internal val Class<*>.isStatic get() = Modifier.isStatic(modifiers)
internal val Class<*>.isAbstract get() = Modifier.isAbstract(modifiers)
internal val Class<*>.isFinal get() = Modifier.isFinal(modifiers)
internal val Class<*>.isPublic get() = Modifier.isPublic(modifiers)

internal infix fun Class<*>.isSubclassOf(superclass: Class<*>): Boolean =
    superclass.isAssignableFrom(this)

internal infix fun Class<*>.isNotSubclassOf(superclass: Class<*>): Boolean =
    !superclass.isAssignableFrom(this)

/** 公共反射访问控制；失败不扩大为隐藏 API 绕过。 */
internal fun Member.makeAccessible(): Boolean {
    val accessible = this as? AccessibleObject ?: return false
    return try {
        @Suppress("DEPRECATION")
        if (!accessible.isAccessible) accessible.isAccessible = true
        true
    } catch (_: RuntimeException) {
        false
    }
}

/** 与原 loadClassOrNull 一致：宿主类缺失或链接失败都视为不存在，但不吞虚拟机错误。 */
internal fun ClassLoader.loadClassOrNull(name: String): Class<*>? = try {
    loadClass(name)
} catch (_: ClassNotFoundException) {
    null
} catch (_: LinkageError) {
    null
}

internal fun String.toClass(loader: ClassLoader? = null, initialize: Boolean = false): Class<*> =
    Class.forName(this, initialize, loader ?: ReflectionTypeLoader::class.java.classLoader)

private object ReflectionTypeLoader
