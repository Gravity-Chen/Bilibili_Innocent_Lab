package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import kotlin.math.roundToLong

/**
 * 由宿主解码帧到达时刻推算主时钟。ijk 按音频主时钟在每帧应显示时 releaseOutputBuffer，
 * 帧到达只会因调度而偏晚、不会偏早，因此窗口内 (PTS − 到达时刻 × 倍速) 的最大值是最接近真实映射的样本；
 * 最大值与中位数之差作为误差上界报告给 [FrameTimeline]，由其按配置上界拒绝过抖的时钟。
 *
 * PTS 回跳、前跳超过 [discontinuityUs] 或长时间无帧后恢复（暂停/seek）都报告 DISCONTINUITY，
 * 外层须以新 epoch 重建；倍速只能由宿主已验证的设置传入，变化同样视为不连续。
 */
internal class FrameHostClock(
    private val window: Int = 32,
    private val discontinuityUs: Long = 250_000L,
    private val minSamples: Int = 4,
    private val minUncertaintyUs: Long = 500L,
    /**
     * 每次快照最多移动的映射量。下包络在更早样本到达或最佳样本移出窗口时会跳 1–3 ms（真机），
     * 时间线会把超出误差预算的跳变视为目标时刻改变而撤销在途提案；限速后变化平滑，大偏差仍直接跳过去。
     */
    private val maxSlewUs: Long = 200L,
    private val jumpUs: Long = 8_000L
) {
    enum class Update { ACCEPTED, DISCONTINUITY, REJECTED }

    private val offsets = DoubleArray(window)
    private var count = 0
    private var cursor = 0
    private var lastPtsUs = Long.MIN_VALUE
    private var lastArrivalNs = Long.MIN_VALUE
    private var speed = 1.0
    private var applied: Double? = null

    init {
        require(window in 8..256 && discontinuityUs > 0L && minSamples in 2..window && minUncertaintyUs >= 0L &&
            maxSlewUs > 0L && jumpUs > maxSlewUs)
    }

    @Synchronized fun feed(ptsUs: Long, arrivalNs: Long, playbackSpeed: Double = 1.0): Update {
        if (ptsUs < 0L || !playbackSpeed.isFinite() || playbackSpeed <= 0.0) return Update.REJECTED
        if (count > 0 && arrivalNs < lastArrivalNs) return Update.REJECTED
        val restart = count > 0 && (playbackSpeed != speed || ptsUs < lastPtsUs ||
            predictedUs(arrivalNs)?.let { kotlin.math.abs(ptsUs - it) > discontinuityUs } != false)
        if (restart) reset()
        speed = playbackSpeed
        offsets[cursor] = ptsUs - arrivalNs / 1_000.0 * speed
        cursor = (cursor + 1) % window
        if (count < window) count++
        lastPtsUs = ptsUs
        lastArrivalNs = arrivalNs
        return if (restart) Update.DISCONTINUITY else Update.ACCEPTED
    }

    /** 样本不足时返回 null；discontinuity 由外层 epoch 提供，时钟本身不发明代次。 */
    @Synchronized fun snapshot(nowNs: Long, discontinuity: Long): FrameTimeline.ClockSnapshot? {
        if (count < minSamples || nowNs < lastArrivalNs) return null
        val samples = offsets.copyOf(count).also { it.sort() }
        val best = samples.last()
        val median = samples[count / 2]
        val previous = applied
        val offset = when {
            previous == null || kotlin.math.abs(best - previous) > jumpUs -> best
            else -> previous + (best - previous).coerceIn(-maxSlewUs.toDouble(), maxSlewUs.toDouble())
        }
        applied = offset
        val media = (offset + nowNs / 1_000.0 * speed).roundToLong()
        if (media < 0L) return null
        val uncertainty = maxOf(minUncertaintyUs, (best - median).roundToLong() + kotlin.math.abs(best - offset).roundToLong())
        return FrameTimeline.ClockSnapshot(media, nowNs, speed, false, discontinuity, uncertainty)
    }

    @Synchronized fun reset() {
        count = 0; cursor = 0; lastPtsUs = Long.MIN_VALUE; lastArrivalNs = Long.MIN_VALUE; applied = null
    }

    private fun predictedUs(arrivalNs: Long): Long? {
        if (count == 0) return null
        val latest = offsets[(cursor - 1 + window) % window]
        return (latest + arrivalNs / 1_000.0 * speed).roundToLong()
    }
}
