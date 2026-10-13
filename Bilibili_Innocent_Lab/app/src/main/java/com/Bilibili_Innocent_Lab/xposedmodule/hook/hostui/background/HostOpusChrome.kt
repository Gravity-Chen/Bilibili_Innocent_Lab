package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lumen.coacervation.engine.host.LumenContentSource
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 图文保留宿主栏位和滚动动画；退出中的关注提示必须裁剪在底栏上方。 */
internal class HostOpusChrome private constructor(root: ViewGroup, guide: View, bottom: View,
    top: View, private val owned: MutableMap<View, Drawable>) : AutoCloseable {
    private val root = WeakReference(root)
    private val guide = WeakReference(guide)
    private val bottom = WeakReference(bottom)
    private val top = WeakReference(top)
    private val originalIndex = root.indexOfChild(guide)
    private val originalParams = guide.layoutParams
    private val viewport = WeakReference(FrameLayout(root.context).apply {
        clipChildren = true
        clipToPadding = true
    })
    private val bindings = WeakHashMap<View, LumenSurfaceBinding>()
    private var session: LumenSurfaceSession? = null
    private var dark: Boolean? = null
    private var wallpaper: Drawable? = null
    private var samplingState = Long.MIN_VALUE
    private val source = object : LumenContentSource {
        override val coordinateView: View get() = requireNotNull(this@HostOpusChrome.root.get())
        override val excludesSurfaces: Boolean = true
        override fun drawContent(canvas: Canvas) {
            // 只取独立壁纸，不绘制页面子树，避免采样到栏位自身和封面播放器。
            this@HostOpusChrome.root.get()?.background?.draw(canvas)
        }
    }

    init {
        val clip = requireNotNull(viewport.get())
        root.removeView(guide)
        root.addView(clip, originalIndex, originalParams)
        // 宿主仍持有原 View，并继续按 61dp 平移；外层视口始终位于底栏上方。
        clip.addView(guide, FrameLayout.LayoutParams(-1, -1))
    }

    fun apply(night: Boolean) {
        val root = root.get() ?: return
        val session = session ?: LumenSurfaceSession(root.context, LumenPalette.neutral(night)).also { session = it }
        val changed = dark != night
        if (changed) { session.updatePalette(LumenPalette.neutral(night)); dark = night }
        fun surface(view: View?, header: Boolean = false) {
            view ?: return
            val options = options(night, header)
            val binding = bindings[view]
            if (binding?.isBound != true || owned[view] !== view.background) {
                binding?.close()
                bindings[view] = session.bindSource(view, options, source.takeIf { header })
                view.background?.let { owned[view] = it }
            } else if (changed) binding.update(options)
        }
        surface(bottom.get())
        surface(guide.get())
        val top = top.get()
        // 封面阶段沿用原生圆形返回/更多按钮；吸顶文字出现后承托整行标题。
        val sticky = listOf("opus_author", "opus_top_name").any { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            id != 0 && root.findViewById<View>(id)?.visibility == View.VISIBLE
        }
        if (sticky) surface(top, header = true)
        else top?.let { bindings.remove(it)?.close(); owned.remove(it) }
        val background = root.background
        val state = (background as? HostBackgroundDrawable)?.samplingState ?: 0L
        if (wallpaper !== background || samplingState != state || changed) {
            wallpaper = background; samplingState = state
            session.notifyContentChanged()
        }
        // 图文封面在状态栏以下，系统文字应按壁纸深浅显示，不跟随封面按钮的白色。
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val decor = root.rootView
        val flags = if (night) decor.systemUiVisibility and mask.inv() else decor.systemUiVisibility or mask
        if (decor.systemUiVisibility != flags) decor.systemUiVisibility = flags
    }

    private fun options(night: Boolean, header: Boolean) = LumenSurfaceOptions(
        material = if (header) LumenSurfaceMaterial.FROSTED else LumenSurfaceMaterial.STATIC,
        role = SurfaceRole.TOP_BAR, radiusDp = 0f,
        color = if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
        tintOpacity = if (header) .78f else .94f, fallbackTintOpacity = .94f,
        edgeEnabled = false, backdropOpacity = 1f,
        sampling = LumenSurfaceSampling(enabled = header, refractionEnabled = false,
            blurRadiusDp = 16f, softwareBlurRadiusDp = 10f, maxSoftwarePixels = 48_000)
    )

    override fun close() {
        session?.close(); session = null
        for (view in bindings.keys) owned.remove(view)
        bindings.clear()
        val root = root.get() ?: return
        val guide = guide.get() ?: return
        val viewport = viewport.get() ?: return
        if (guide.parent === viewport && viewport.parent === root) {
            viewport.removeView(guide)
            root.removeView(viewport)
            root.addView(guide, originalIndex.coerceAtMost(root.childCount), originalParams)
        }
    }

    companion object {
        fun create(root: ViewGroup, owned: MutableMap<View, Drawable>): HostOpusChrome? {
            fun find(name: String): View? {
                val id = root.resources.getIdentifier(name, "id", root.context.packageName)
                return if (id == 0) null else root.findViewById(id)
            }
            val guide = find("opus_follow_guide") ?: return null
            val bottom = find("opus_bottom_layout") ?: return null
            val top = find("opus_top_bar") ?: return null
            if (guide.parent !== root || bottom.parent !== root || top.parent !== root) return null
            return HostOpusChrome(root, guide, bottom, top, owned)
        }
    }
}
