package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import java.lang.ref.WeakReference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog

/** 保留个人中心立绘，在原主视觉容器上加可读性渐变和底部圆角；不创建页面叠层。 */
internal class HostGarbHeroBinding(anchor: View) {
    private val anchor = WeakReference(anchor)
    private val id = anchor.resources.getIdentifier("main_top_bg_imageview", "id", "tv.danmaku.bili")
    private var reported = false
    private var hero = WeakReference<View>(null)
    private var dirty = true
    private var originalForeground: Drawable? = null
    private var originalOutline: ViewOutlineProvider? = null
    private var originalClip = false
    private var applied = false
    private val scrim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x66000000, 0x94000000.toInt(), 0xAC000000.toInt()))
    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val radius = 24f * view.resources.displayMetrics.density
            // 顶边继续延伸到状态栏，只在底边收成圆角。
            outline.setRoundRect(0, -radius.toInt(), view.width, view.height, radius)
        }
    }

    fun invalidate() { dirty = true }

    fun refresh(personalized: Boolean) {
        if (id == 0) return
        if (dirty || hero.get()?.let { it.parent == null } == true) {
            val image = anchor.get()?.rootView?.findViewById<View>(id)
            // 动态装扮的 TextureView 与图片是兄弟节点，给图片加 foreground 会被视频盖住。
            // 主视觉容器只包裹图片/动画，昵称与操作按钮仍在它的外层，保持原生交互。
            val next = image?.parent as? View ?: image
            if (next !== hero.get()) {
                restore()
                hero = WeakReference(next)
            }
            dirty = false
        }
        val view = hero.get() ?: return
        if (!personalized) { restore(); return }
        if (!applied) {
            originalForeground = view.foreground
            originalOutline = view.outlineProvider
            originalClip = view.clipToOutline
            applied = true
            if (!reported) {
                reported = true
                ModernHookLog.info("[BIL] 个性装扮个人中心融合已接入(view=${view.javaClass.simpleName}, visible=${view.isShown})")
            }
        } else if (view.foreground !== scrim) originalForeground = view.foreground
        if (view.foreground !== scrim) view.foreground = scrim
        if (view.outlineProvider !== outline) view.outlineProvider = outline
        if (!view.clipToOutline) view.clipToOutline = true
    }

    fun restore() {
        val view = hero.get()
        if (view != null && applied) {
            if (view.foreground === scrim) view.foreground = originalForeground
            if (view.outlineProvider === outline) {
                view.outlineProvider = originalOutline
                view.clipToOutline = originalClip
            }
        }
        originalForeground = null; originalOutline = null; applied = false
    }
}
