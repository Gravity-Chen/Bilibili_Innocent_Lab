package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 仅包装模块自己发起的重解析 listener；不登记 Proxy/delegate，不影响宿主其他请求。 */
internal class StoryQualityReparseGate(
    private val listenerType: Class<*>,
    private val success: Method,
    private val permitted: (Lease) -> Boolean,
    private val failed: (Lease) -> Unit
) {
    class Lease(owner: Any, params: Any, val item: PlayerQualitySessions.ItemKey, val token: PlayerQualitySessions.Token) {
        private val ownerReference = WeakReference(owner)
        private val paramsReference = WeakReference(params)
        fun owner(): Any? = ownerReference.get()
        fun params(): Any? = paramsReference.get()
    }

    private class Frame(val lease: Lease, val resource: Any?)
    private val active = ThreadLocal<Frame>()
    private val error = runCatching {
        if (!listenerType.isInterface || Modifier.isStatic(success.modifiers) ||
            success.returnType != Void.TYPE || success.parameterCount != 1 || success.parameterTypes[0].isPrimitive ||
            Throwable::class.java.isAssignableFrom(success.parameterTypes[0])) return@runCatching null
        val methods = listenerType.methods.filter { !Modifier.isStatic(it.modifiers) && !objectMethod(it) }
            .distinctBy { it.name to it.parameterTypes.toList() }
        if (methods.size != 2 || methods.count { it == success } != 1) return@runCatching null
        methods.singleOrNull { it != success && it.name == "onError" && it.returnType == Void.TYPE &&
            it.parameterCount == 0 }
    }.getOrNull()

    val available: Boolean get() = error != null

    fun wrap(delegate: Any, lease: Lease): Any? {
        val error = error ?: return null
        if (!listenerType.isInstance(delegate)) return null
        return runCatching {
            val target = AtomicReference<Any?>(delegate)
            val terminal = AtomicBoolean(false)
            val failedOnce = AtomicBoolean(false)
            fun failOnce() { if (failedOnce.compareAndSet(false, true)) fail(lease) }
            Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                if (objectMethod(method)) {
                    when (method.name) {
                        "equals" -> args?.getOrNull(0) === proxy
                        "hashCode" -> System.identityHashCode(proxy)
                        else -> "StoryQualityReparseListener"
                    }
                } else if ((method == success || method == error) && terminal.compareAndSet(false, true)) {
                    // 在任何 guard/宿主回调之前释放 handler 的强引用，重复终态不能再调用。
                    val listener = target.getAndSet(null)
                    if (listener == null) null
                    else if (!runCatching { permitted(lease) }.getOrDefault(false)) {
                        failOnce()
                        null
                    } else if (method == error) {
                        try { invoke(listener, error, args, ::failOnce) } finally { failOnce() }
                    } else {
                        val previous = active.get()
                        active.set(Frame(lease, args?.getOrNull(0)))
                        try {
                            invoke(listener, success, args, ::failOnce)
                        } catch (throwable: Throwable) {
                            failOnce()
                            throw throwable
                        } finally {
                            if (previous == null) active.remove() else active.set(previous)
                        }
                    }
                } else null
            }
        }.getOrNull()
    }

    /** 只在 success delegate 的同步调用期间可见；返回/抛错即恢复父调用帧。 */
    fun current(): Lease? = active.get()?.lease

    /** 与 current 使用同一个同步帧，保持原成功入参身份；不能跨异步任务保存。 */
    fun resource(): Any? = active.get()?.resource

    private fun invoke(delegate: Any, method: Method, args: Array<out Any?>?, failed: () -> Unit): Any? = try {
        method.invoke(delegate, *(args ?: emptyArray()))
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    } catch (_: Throwable) {
        // 反射/模块 ABI 失败只丢弃本模块请求；真正的 delegate 异常由上面原样交回。
        failed()
        null
    }

    private fun fail(lease: Lease) { runCatching { failed(lease) } }

    private companion object {
        fun objectMethod(method: Method): Boolean = when (method.name) {
            "equals" -> method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(Any::class.java))
            "hashCode" -> method.returnType == Int::class.javaPrimitiveType && method.parameterCount == 0
            "toString" -> method.returnType == String::class.java && method.parameterCount == 0
            else -> false
        }
    }
}
