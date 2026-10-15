package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi

/** API 29 类型隔离；亚克力只缩小共享的柔化材质，卡片边框与前景仍按原尺寸绘制。 */
@RequiresApi(29)
internal class HostCardMaterialSceneApi29(private val scale: Float = 1f) : HostCardMaterialScene {
    private val node = RenderNode("BIL-shared-card-material").apply {
        // 页面内重复引用同一个材质场，使用一个有界中间层复用 GPU 合成结果。
        setUseCompositingLayer(true, null)
    }
    override fun record(drawable: Drawable, width: Int, height: Int) {
        val rasterWidth = kotlin.math.ceil(width * scale).toInt().coerceAtLeast(1)
        val rasterHeight = kotlin.math.ceil(height * scale).toInt().coerceAtLeast(1)
        node.setPosition(0, 0, rasterWidth, rasterHeight)
        val canvas = node.beginRecording(rasterWidth, rasterHeight)
        try {
            if (scale != 1f) canvas.scale(scale, scale)
            drawable.draw(canvas)
        } finally { node.endRecording() }
    }
    override fun draw(canvas: Canvas) {
        if (scale == 1f) { canvas.drawRenderNode(node); return }
        val saved = canvas.save()
        try { canvas.scale(1f / scale, 1f / scale); canvas.drawRenderNode(node) }
        finally { canvas.restoreToCount(saved) }
    }
    override fun close() = node.discardDisplayList()
}
