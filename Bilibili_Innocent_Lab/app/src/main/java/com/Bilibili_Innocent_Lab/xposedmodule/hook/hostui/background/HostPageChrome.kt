package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.highcapable.betterandroid.ui.extension.insets.factory.createRootWindowInsetsWrapper
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 页面顶部的语义层。只采样祖先壁纸，保留原生点击、筛选和已有卡片材质。 */
internal class HostPageChrome(root: ViewGroup, private val owned: MutableMap<View, Drawable>) : AutoCloseable {
    private enum class Role { TOOLBAR, FILTER, AUTHORS }
    private class Target(val role: Role) {
        var binding: LumenSurfaceBinding? = null
        var drawable: Drawable? = null
    }
    private val root = WeakReference(root)
    private val targets = WeakHashMap<View, Target>()
    private var session: LumenSurfaceSession? = null
    private var source: HostChromeContentSource? = null
    private var dark: Boolean? = null
    private var version: Long? = null
    private var statusBand: View? = null
    private var statusBinding: LumenSurfaceBinding? = null
    private var statusRole: Role? = null
    private val location = IntArray(2)
    private val visibleBounds = Rect()

    fun discover() {
        val root = root.get() ?: return
        val found = HashMap<View, Role>()
        fun visit(view: View, depth: Int) {
            if (depth > 24 || view is ImageView || HostBackgroundPages.protectedAncestor(view)) return
            if (view !== root && HostBackgroundPages.owner(view)?.let { it !== root } == true) return
            val name = name(view)
            val role = when (name) {
                "nav_top_bar", "search_input_layout" -> Role.TOOLBAR
                "tab_box", "container_strip", "header_container" -> Role.FILTER
                "top_container" -> if (view is ViewGroup && (0 until view.childCount).any {
                    name(view.getChildAt(it)) == "tab_layout"
                }) Role.FILTER else null
                "dy_recycler" -> Role.AUTHORS
                else -> null
            }
            if (role != null) { found[view] = role; return }
            // ViewPager2 内部的 RecyclerView 承载页面，收藏筛选栏位于其中；
            // 普通内容列表仍不进入，避免遍历卡片或给卡片子组件套顶栏材质。
            if (view !is ViewGroup || !view.javaClass.name.startsWith("androidx.viewpager2.") &&
                generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        for (view in targets.keys.toList()) if (view !in found) {
            targets.remove(view)?.binding?.close()
            owned.remove(view)
        }
        for ((view, role) in found) targets.getOrPut(view) { Target(role) }
    }

    fun apply(night: Boolean) {
        val root = root.get() ?: return
        if (targets.isEmpty()) { hideStatusBar(); return }
        val palette = LumenPalette.neutral(night)
        val changed = dark != night
        val active = session ?: LumenSurfaceSession(root.context, palette,
            LumenSurfaceSessionOptions(maxSurfaces = 8, pauseWhenWindowUnfocused = false)).also {
            session = it
            // includeViewContent=false 不会把栏位自身、媒体或其他玻璃录入采样。
            source = HostChromeContentSource(root, palette.background, includeViewContent = false)
        }
        if (changed) {
            active.updatePalette(palette)
            source?.windowBaseColor = palette.background
            dark = night
        }
        for ((view, target) in targets) {
            if (!view.isShown) continue
            if (target.binding?.isBound != true || view.background !== target.drawable) {
                target.binding?.close()
                val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                target.binding = active.bindSource(view, options(target.role, night), source)
                target.drawable = view.background
                target.drawable?.let { owned[view] = it }
                view.setPadding(padding[0], padding[1], padding[2], padding[3])
            } else if (changed) target.binding?.update(options(target.role, night))
        }
        syncStatusBar(active, night, changed)
        val next = source?.contentVersion
        if (version != next || changed) { version = next; active.notifyContentChanged() }
    }

    private fun hideStatusBar() {
        statusBinding?.close(); statusBinding = null
        statusRole = null
        statusBand?.visibility = View.GONE
    }

    private fun syncStatusBar(active: LumenSurfaceSession, night: Boolean, changed: Boolean) {
        val root = root.get() ?: return
        // 只有窗口根能够绘制系统图标背后的区域；子 Fragment 不能重复加一层。
        if (root !== root.rootView || root !is FrameLayout) return
        val inset = root.createRootWindowInsetsWrapper()?.statusBars()?.top ?: 0
        if (inset <= 0) { hideStatusBar(); return }
        val tolerance = root.resources.displayMetrics.density * 2f
        val role = targets.entries.firstOrNull { (view, _) ->
            if (!visibleChrome(view, root)) false
            else {
                view.getLocationInWindow(location)
                location[1] <= inset + tolerance && location[1] + view.height > inset
            }
        }?.value?.role
        if (role == null) { hideStatusBar(); return }
        val band = statusBand ?: View(root.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(this, FrameLayout.LayoutParams(-1, inset, Gravity.TOP))
        }.also { statusBand = it }
        if (band.layoutParams.height != inset) {
            band.layoutParams = band.layoutParams.apply { height = inset }
        }
        band.visibility = View.VISIBLE
        // 与相邻栏共用源、模糊半径和色罩，保留壁纸的窗口坐标，系统图标由系统照常绘制。
        if (statusBinding?.isBound != true || statusRole != role) {
            statusBinding?.close()
            statusBinding = active.bindSource(band, options(role, night), source)
            statusRole = role
        } else if (changed) statusBinding?.update(options(role, night))
    }

    private fun visibleChrome(view: View, root: ViewGroup): Boolean {
        if (!view.isShown || !view.getGlobalVisibleRect(visibleBounds) || visibleBounds.width() < root.width * .9f) return false
        // 首页栏可能仍挂在离屏分页里，或由祖先 alpha 隐藏；isShown 本身不排除这两种状态。
        var node: View? = view
        while (node != null && node !== root) {
            if (node.alpha <= .01f || node.width <= 0 || node.height <= 0) return false
            node = node.parent as? View
        }
        return true
    }

    private fun options(role: Role, night: Boolean) = LumenSurfaceOptions(
        material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.TOP_BAR, radiusDp = 0f,
        color = if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
        tintOpacity = when (role) { Role.TOOLBAR -> .62f; Role.FILTER -> .48f; Role.AUTHORS -> .38f },
        fallbackTintOpacity = .88f, backdropOpacity = 1f,
        edgeEnabled = false,
        sampling = LumenSurfaceSampling(refractionEnabled = false, blurRadiusDp = 18f,
            softwareBlurRadiusDp = 12f, maxSoftwarePixels = 48_000)
    )

    override fun close() {
        hideStatusBar()
        statusBand?.let { (it.parent as? ViewGroup)?.removeView(it) }
        statusBand = null
        session?.close(); session = null
        source = null; dark = null; version = null
        for ((view, target) in targets) {
            owned.remove(view)
            target.binding = null; target.drawable = null
        }
    }

    private fun name(view: View) = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
}
