package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Compose 多个 Modifier 复用同一个视口；生命周期由 Scope 的 owner attach listener 持有。 */
internal object HostCardMaterialRegistry {
    private val scopes = WeakHashMap<ViewGroup, WeakReference<HostCardMaterialScope>>()
    fun scope(view: View, night: Boolean, material: HostCardMaterial): HostCardMaterialScope? {
        val owner = view as? ViewGroup ?: view.parent as? ViewGroup ?: return null
        val previous = scopes[owner]?.get()
        if (previous != null && previous.materialKind != material) previous.close()
        val scope = previous?.takeUnless { it.closed }
            ?: HostCardMaterialScope(owner, night, view.context, material).also { scopes[owner] = WeakReference(it) }
        scope.refresh(night)
        return scope
    }
}
