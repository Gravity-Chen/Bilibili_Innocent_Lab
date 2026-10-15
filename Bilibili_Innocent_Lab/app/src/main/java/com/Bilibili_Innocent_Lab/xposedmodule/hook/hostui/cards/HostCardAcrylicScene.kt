package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.drawable.Drawable

/** 只缓存引擎的绘制结果，不实现材质、混合或模糊。 */
internal interface HostCardAcrylicScene : AutoCloseable {
    fun record(drawable: Drawable, width: Int, height: Int)
    fun draw(canvas: Canvas)
}

internal class HostCardAcrylicPicture : HostCardAcrylicScene {
    private val picture = Picture()
    override fun record(drawable: Drawable, width: Int, height: Int) {
        val canvas = picture.beginRecording(width, height)
        try { drawable.draw(canvas) } finally { picture.endRecording() }
    }
    override fun draw(canvas: Canvas) = canvas.drawPicture(picture)
    override fun close() = Unit
}
