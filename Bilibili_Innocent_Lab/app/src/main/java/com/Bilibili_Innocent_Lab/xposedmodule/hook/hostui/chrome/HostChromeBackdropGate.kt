package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One verified background invocation owns its decorations, never its nested foreground. */
internal class HostChromeBackdropGate {
    private val foreground = HashSet<Any>()
    val decorative: Boolean get() = foreground.isEmpty()

    fun enterForeground(): Any = Any().also(foreground::add)
    fun exitForeground(token: Any) { foreground.remove(token) }

    companion object {
        // Registrar deduplication keeps earlier callbacks after a partial installation. A retry
        // must arm those callbacks too. Values contain strings/flags, never a Class or host View.
        private val installations = WeakHashMap<Class<*>, MutableMap<List<String>, AtomicBoolean>>()

        fun installation(owner: Class<*>, method: String, parameters: List<String>): AtomicBoolean =
            synchronized(installations) {
                installations.getOrPut(owner) { HashMap() }.getOrPut(listOf(method) + parameters) { AtomicBoolean(false) }
            }
    }
}
