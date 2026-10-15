package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi

/** API 29 类型隔离；多个卡片引用同一份页面材质，不能逐卡片重录模糊节点。 */
@RequiresApi(29)
internal class HostCardMaterialSceneApi29 : HostCardMaterialScene {
    private val node = RenderNode("BIL-shared-card-material").apply {
        // 页面内重复引用同一个材质场，使用一个有界中间层复用 GPU 合成结果。
        setUseCompositingLayer(true, null)
    }
    override fun record(drawable: Drawable, width: Int, height: Int) {
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording(width, height)
        try { drawable.draw(canvas) } finally { node.endRecording() }
    }
    override fun draw(canvas: Canvas) = canvas.drawRenderNode(node)
    override fun close() = node.discardDisplayList()
}
