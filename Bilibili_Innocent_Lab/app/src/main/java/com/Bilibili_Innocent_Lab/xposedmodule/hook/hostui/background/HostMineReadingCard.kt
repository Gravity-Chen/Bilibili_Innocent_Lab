package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 整个功能列表共用一张卡片；分组只画内部细线，不各自增加圆角、描边或留白。 */
internal class HostMineReadingCard(list: ViewGroup) : AutoCloseable {
    private val listRef = WeakReference(list)
    private val backgrounds = WeakHashMap<View, Drawable?>()
    private var night = false
    private var geometry = Long.MIN_VALUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividers = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val list = listRef.get() ?: return
            val density = list.resources.displayMetrics.density
            paint.color = if (night) 0x1FFFFFFF else 0x1F000000
            paint.strokeWidth = density.coerceAtLeast(1f) * .5f
            for (i in 0 until list.childCount) {
                val section = list.getChildAt(i)
                if (!backgrounds.containsKey(section)) continue
                val y = section.top + section.translationY - list.scrollY
                if (y <= 1f || y >= list.height - 1f) continue
                canvas.drawLine(16f * density, y, list.width - 16f * density, y, paint)
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    init { list.overlay.add(dividers) }

    fun sync(night: Boolean) {
        val paletteChanged = this.night != night
        this.night = night
        val list = listRef.get() ?: return
        val sections = (0 until list.childCount).map { list.getChildAt(it) }.filter { section ->
            section is ViewGroup && (0 until section.childCount).any {
                HostViewResourceNames.name(section.getChildAt(it)) == "item_recycle"
            }
        }
        for (view in backgrounds.keys.toList()) if (view !in sections) restore(view)
        for (section in sections) {
            if (section.background != null) {
                // 宿主换肤或复用后更新快照，释放时不恢复更早的主题状态。
                backgrounds[section] = section.background
                val l = section.paddingLeft; val t = section.paddingTop
                val r = section.paddingRight; val b = section.paddingBottom
                section.background = null
                section.setPadding(l, t, r, b)
            } else if (!backgrounds.containsKey(section)) backgrounds[section] = null
        }
        var next = list.width.toLong() * 31 + list.height
        next = next * 31 + list.scrollY
        for (section in sections) {
            next = next * 31 + section.top
            next = next * 31 + section.translationY.toBits()
        }
        if (next != geometry || paletteChanged) {
            geometry = next
            dividers.setBounds(0, 0, list.width, list.height)
            dividers.invalidateSelf()
        }
    }

    private fun restore(view: View) {
        val original = backgrounds.remove(view)
        if (view.background == null) {
            val l = view.paddingLeft; val t = view.paddingTop
            val r = view.paddingRight; val b = view.paddingBottom
            view.background = original
            view.setPadding(l, t, r, b)
        }
    }

    override fun close() {
        listRef.get()?.overlay?.remove(dividers)
        for (view in backgrounds.keys.toList()) restore(view)
    }
}
