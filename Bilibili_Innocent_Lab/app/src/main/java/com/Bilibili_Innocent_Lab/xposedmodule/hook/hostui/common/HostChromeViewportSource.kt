package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import kotlin.math.ceil
import kotlin.math.floor

/** 顶/底栏各自只录制实际取样范围；探针仍读取原生前景，保持原来的对比度补偿语义。 */
internal class HostChromeViewportSource(
    private val source: HostChromeContentSource,
    private val regions: (consumer: (View, Float, Float, Float) -> Unit) -> Unit
) : LumenVersionedContentSource, AutoCloseable {
    private val content = source.coordinateView as ViewGroup
    private val mapping = ViewSamplingMatrix()
    private val matrix = Matrix()
    private val union = RectF()
    private val region = RectF()
    private var unsafe = false
    private var drawing = false
    override val coordinateView = object : View(content.context) {
        // OverlayViewGroup has no ordinary parent chain; its default isShown() is false.
        // The proxy has exactly the same lifecycle and visible region as its content owner.
        override fun isShown(): Boolean = content.isShown

        override fun onDraw(canvas: Canvas) {
            // Android may record even an alpha=0 overlay. Only the software legibility probe
            // needs pixels here; re-entering the owner's hardware recording would recurse.
            if (!canvas.isHardwareAccelerated && !drawing) draw(canvas, foregroundOnly = true)
        }
    }.apply {
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        content.overlay.add(this)
    }
    override val observeCoordinateViewInvalidations: Boolean get() = false
    override val sourceEpoch: Long get() = source.sourceEpoch
    override val excludesSurfaces: Boolean get() = source.excludesSurfaces
    override val availability: LumenSourceAvailability get() =
        if (unsafe) LumenSourceAvailability.PROHIBITED else source.availability
    override val contentVersion: Long
        get() {
            var version = source.contentVersion
            if (content.isDirty) version = version * 31 + content.drawingTime
            version = version * 31 + coordinateView.left
            version = version * 31 + coordinateView.top
            version = version * 31 + coordinateView.width
            return (version * 31 + coordinateView.height) and Long.MAX_VALUE
        }

    fun sync(): Boolean {
        union.setEmpty()
        unsafe = false
        regions { view, insetX, insetY, padding ->
            if (!source.excludesSurfaces) {
                var node: View? = view
                while (node != null) {
                    if (node === content) { unsafe = true; break }
                    node = node.parent as? View
                }
            }
            if (!view.isShown || view.width <= 0 || view.height <= 0 || !mapping.sourceToTarget(view, content, matrix)) return@regions
            val x = insetX.coerceIn(0f, (view.width - 1f) / 2f)
            val y = insetY.coerceIn(0f, (view.height - 1f) / 2f)
            region.set(x - padding, y - padding, view.width - x + padding, view.height - y + padding)
            matrix.mapRect(region)
            union.union(region)
        }
        if (union.isEmpty || !union.intersect(0f, 0f, content.width.toFloat(), content.height.toFloat()))
            union.set(0f, 0f, content.width.toFloat(), content.height.toFloat())
        val left = floor(union.left).toInt(); val top = floor(union.top).toInt()
        val right = ceil(union.right).toInt(); val bottom = ceil(union.bottom).toInt()
        val changed = coordinateView.left != left || coordinateView.top != top ||
            coordinateView.right != right || coordinateView.bottom != bottom
        if (changed) coordinateView.layout(left, top, right, bottom)
        return changed
    }

    override fun drawContent(canvas: Canvas) = draw(canvas, foregroundOnly = false)

    private fun draw(canvas: Canvas, foregroundOnly: Boolean) {
        if (drawing || unsafe || !mapping.sourceToTarget(content, coordinateView, matrix)) return
        drawing = true
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, coordinateView.width, coordinateView.height)
            canvas.concat(matrix)
            canvas.clipRect(0, 0, content.width, content.height)
            if (foregroundOnly) {
                canvas.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
                content.draw(canvas)
            } else source.drawContent(canvas)
        } finally { canvas.restoreToCount(saved); drawing = false }
    }

    override fun close() { content.overlay.remove(coordinateView) }
}
