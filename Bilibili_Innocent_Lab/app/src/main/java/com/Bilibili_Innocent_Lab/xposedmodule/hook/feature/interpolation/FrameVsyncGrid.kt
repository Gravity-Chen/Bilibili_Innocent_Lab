package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 呈现线程的 vsync 网格：驱动线程每个 vsync 回调一次 [onVsync]，但驱动线程忙时会漏掉回调
 * （真机 120 Hz 下驱动间隔可达 18 ms）。若只看最近一次回调，漏掉一个 vsync 会让可提交窗口一次前移两个刷新，
 * 窗口宽于内容周期（90 fps = 11.1 ms）时两帧同时可提交，前一帧被作废，丢帧率越过回退线。
 * 这里按实测显示周期从最近一次回调外推当前 vsync，窗口每个刷新只前移一个显示周期，每个刷新至多放行一帧。
 *
 * 显示周期取最近 [WINDOW] 个回调间隔的最小值（内容周期为初值）：漏回调只会产生更长的整倍数间隔，不会拉长估计；
 * 面板实际刷新低于目标（目标按面板模式定为 90 fps、面板空闲停在 60 Hz）时一个窗口内即跟上真实周期。
 * 纯状态，时间由调用方传入。
 */
internal class FrameVsyncGrid(private val contentPeriodNs: Long) {
    init { require(contentPeriodNs > 0L) }

    @Volatile var lastNs = 0L
        private set
    @Volatile var periodNs = contentPeriodNs
        private set

    private val deltas = LongArray(WINDOW)
    private var count = 0
    private var cursor = 0

    /** 返回是否为新的 vsync（时间未前进的重复回调忽略）。 */
    fun onVsync(vsyncNs: Long): Boolean {
        if (vsyncNs <= lastNs) return false
        if (lastNs != 0L) {
            deltas[cursor] = vsyncNs - lastNs
            cursor = (cursor + 1) % WINDOW
            if (count < WINDOW) count++
            var shortest = Long.MAX_VALUE
            for (i in 0 until count) if (deltas[i] < shortest) shortest = deltas[i]
            periodNs = shortest
        }
        lastNs = vsyncNs
        return true
    }

    /** 没有回调或回调已停（暂停、窗口不可见）：调用方改用时间网格。 */
    fun stale(nowNs: Long): Boolean = lastNs == 0L || nowNs - lastNs >= STALE_PERIODS * contentPeriodNs

    /** 不晚于 nowNs 的最近一个（可能外推的）vsync。 */
    fun current(nowNs: Long): Long = if (nowNs <= lastNs) lastNs else lastNs + (nowNs - lastNs) / periodNs * periodNs

    /**
     * 帧在当前 vsync 后的提前量内到期即可提交。提前量沿用内容周期（60 Hz 实测的相位），
     * 外加半个显示周期的取整余量；窗口宽度恒为一个显示周期。
     */
    fun releasable(dueNs: Long, nowNs: Long): Boolean =
        dueNs <= current(nowNs) + maxOf(contentPeriodNs, periodNs) + periodNs / 2

    /** 帧属于即将到来的这次刷新（窗口一个显示周期宽）：同一刷新里更早的帧应让位给它。 */
    fun sameRefresh(dueNs: Long, nowNs: Long): Boolean = dueNs <= current(nowNs) + periodNs + periodNs / 2

    /** 实测显示周期明显短于内容周期（< 95%）：一帧内容跨多个刷新。 */
    fun fasterThanContent(): Boolean = periodNs * 20 < contentPeriodNs * 19

    /** 实测显示周期明显长于内容周期（> 5%）：面板刷新跟不上目标帧率。 */
    fun slowerThanContent(): Boolean = periodNs * 20 > contentPeriodNs * 21

    /** 距下一个（外推）vsync 的纳秒数，供呈现线程定时复查。 */
    fun untilNextNs(nowNs: Long): Long = current(nowNs) + periodNs - nowNs

    private companion object {
        const val STALE_PERIODS = 4L
        const val WINDOW = 32
    }
}
