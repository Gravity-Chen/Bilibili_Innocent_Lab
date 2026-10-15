package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenContentSource

/** 保留列表原坐标，只录制融合带及模糊所需的邻域，避免为状态栏重画整屏动态。 */
internal class FollowFeedStatusContentSource(
    override val coordinateView: View,
    private val band: View,
    private val paddingPx: Float
) : LumenContentSource {
    private val mapping = ViewSamplingMatrix()
    private val matrix = Matrix()
    private val region = RectF()

    override fun drawContent(canvas: Canvas) {
        val saved = canvas.save()
        try {
            if (mapping.sourceToTarget(band, coordinateView, matrix)) {
                region.set(-paddingPx, -paddingPx, band.width + paddingPx, band.height + paddingPx)
                matrix.mapRect(region)
                canvas.clipRect(region)
            }
            // 与引擎的 View 源保持相同的自身滚动语义；变换失败时完整录制作为回退。
            canvas.translate(-coordinateView.scrollX.toFloat(), -coordinateView.scrollY.toFloat())
            coordinateView.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}
