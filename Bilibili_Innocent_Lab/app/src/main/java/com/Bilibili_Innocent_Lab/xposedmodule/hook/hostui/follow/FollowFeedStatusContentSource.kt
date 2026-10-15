package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import kotlin.math.ceil
import kotlin.math.floor

/** 保留列表原坐标，只录制融合带及模糊所需的邻域，避免为状态栏重画整屏动态。 */
internal class FollowFeedStatusContentSource(
    private val content: View,
    private val band: View,
    private val paddingPx: Float
) : LumenVersionedContentSource, AutoCloseable {
    private val mapping = ViewSamplingMatrix()
    private val matrix = Matrix()
    private val region = RectF()
    // 引擎按 coordinateView 的尺寸分配录制缓冲。单纯 clip Canvas 仍会分配整屏纹理。
    // 代理只提供真实的窗口坐标及小视口，不参与宿主布局、触摸或屏幕绘制。
    private val viewport = (content as? ViewGroup)?.let { owner ->
        View(owner.context).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            owner.overlay.add(this)
        }
    }
    override val coordinateView: View = viewport ?: content
    // alpha=0 的坐标代理不由屏幕绘制清除 dirty；只观察实际内容，避免静止时一直录制。
    override val observeCoordinateViewInvalidations: Boolean get() = false
    override val sourceEpoch: Long get() = 0L
    override val availability: LumenSourceAvailability get() = LumenSourceAvailability.READY
    override val contentVersion: Long
        get() {
            var version = if (content.isDirty) content.drawingTime.coerceAtLeast(0L) else 0L
            // header 的位移可以只更新属性；裁剪窗口挪动时，当帧就须重录这块输入。
            version = version * 31L + coordinateView.left
            version = version * 31L + coordinateView.top
            version = version * 31L + coordinateView.width
            version = version * 31L + coordinateView.height
            return version and Long.MAX_VALUE
        }

    init { sync() }

    fun sync() {
        val viewport = viewport ?: return
        region.set(0f, 0f, content.width.toFloat(), content.height.toFloat())
        if (band.width > 0 && band.height > 0 && mapping.sourceToTarget(band, content, matrix)) {
            region.set(-paddingPx, -paddingPx, band.width + paddingPx, band.height + paddingPx)
            matrix.mapRect(region)
            if (!region.intersect(0f, 0f, content.width.toFloat(), content.height.toFloat()))
                region.set(0f, 0f, content.width.toFloat(), content.height.toFloat())
        }
        val left = floor(region.left).toInt()
        val top = floor(region.top).toInt()
        val right = ceil(region.right).toInt()
        val bottom = ceil(region.bottom).toInt()
        if (viewport.left != left || viewport.top != top || viewport.right != right || viewport.bottom != bottom)
            viewport.layout(left, top, right, bottom)
    }

    override fun drawContent(canvas: Canvas) {
        val saved = canvas.save()
        try {
            if (viewport != null) {
                if (!mapping.sourceToTarget(content, viewport, matrix)) return
                canvas.clipRect(0, 0, viewport.width, viewport.height)
                canvas.concat(matrix)
            } else if (viewport == null && mapping.sourceToTarget(band, content, matrix)) {
                region.set(-paddingPx, -paddingPx, band.width + paddingPx, band.height + paddingPx)
                matrix.mapRect(region)
                canvas.clipRect(region)
            }
            // 与引擎的 View 源保持相同的自身滚动语义。
            canvas.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
            content.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }

    override fun close() { viewport?.let { (content as ViewGroup).overlay.remove(it) } }
}
