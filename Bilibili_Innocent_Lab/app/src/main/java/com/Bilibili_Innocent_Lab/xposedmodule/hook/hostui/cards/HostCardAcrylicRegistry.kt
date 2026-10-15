package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Compose 多个 Modifier 复用同一个视口；生命周期由 Scope 的 owner attach listener 持有。 */
internal object HostCardAcrylicRegistry {
    private val scopes = WeakHashMap<ViewGroup, WeakReference<HostCardAcrylicScope>>()
    fun scope(view: View, night: Boolean): HostCardAcrylicScope? {
        val owner = view as? ViewGroup ?: view.parent as? ViewGroup ?: return null
        val scope = scopes[owner]?.get()?.takeUnless { it.closed }
            ?: HostCardAcrylicScope(owner, night, view.context).also { scopes[owner] = WeakReference(it) }
        scope.refresh(night)
        return scope
    }
}
