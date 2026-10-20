package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameVsyncGridTest {
    private val display120 = 8_333_333L
    private val content90 = 11_111_111L

    private fun FrameVsyncGrid.feed(from: Long, count: Int, period: Long, skip: (Int) -> Boolean = { false }) {
        for (i in 0 until count) if (!skip(i)) onVsync(from + i * period)
    }

    @Test fun `display period converges to the panel and ignores skipped callbacks`() {
        val grid = FrameVsyncGrid(content90)
        // 每 5 个回调漏 1 个：整倍数间隔不能拉长估计。
        grid.feed(1_000_000_000L, 120, display120) { it % 5 == 4 }
        assertEquals(display120.toDouble(), grid.periodNs.toDouble(), 50_000.0)
    }

    @Test fun `a panel idling below the target is followed at its real period`() {
        val grid = FrameVsyncGrid(content90)
        val period60 = 16_666_666L
        grid.feed(1L, 40, period60)
        assertEquals(period60, grid.periodNs)
        assertTrue(grid.slowerThanContent())
        // 面板随后升到 120 Hz：新的短间隔立即生效。
        grid.feed(grid.lastNs + display120, 4, display120)
        assertEquals(display120, grid.periodNs)
        assertFalse(grid.slowerThanContent())
    }

    @Test fun `a skipped callback still advances the grid one refresh at a time`() {
        val grid = FrameVsyncGrid(content90)
        grid.feed(0L + 1, 60, display120)
        val last = grid.lastNs
        // 驱动线程漏掉下一个回调：外推仍落在真实 vsync 上。
        assertEquals(last + grid.periodNs, grid.current(last + display120 + 1_000_000L))
        assertEquals(last, grid.current(last + 1_000_000L))
    }

    /**
     * 30→90 fps 在 120 Hz 面板上，驱动回调频繁漏掉：每个刷新至多放行一帧，按 vsync 逐个交付不作废任何一帧
     * （只看最近回调时，漏掉一个回调会让两帧同时可提交、前一帧被作废）。
     */
    @Test fun `90 fps content on a 120 Hz panel never releases two frames in one refresh`() {
        val grid = FrameVsyncGrid(content90)
        val start = 1_000_000_000L
        grid.feed(start, 30, display120)
        val base = grid.lastNs
        val dues = ArrayDeque((0 until 200).map { base + 30_000_000L + it * content90 })
        var dropped = 0
        var released = 0
        for (k in 1..300) {
            val vsync = base + k * display120
            // 驱动线程忙：约三分之一的 vsync 没有回调。
            if (k % 3 != 0) grid.onVsync(vsync)
            val now = vsync + 500_000L
            val head = dues.firstOrNull() ?: break
            if (!grid.releasable(head, now)) continue
            dues.removeFirst()
            val next = dues.firstOrNull()
            if (next != null && grid.releasable(next, now)) dropped++ else released++
        }
        assertEquals(0, dropped)
        assertTrue("released $released", released > 150)
    }

    @Test fun `60 fps content on a 60 Hz panel keeps the previous release window`() {
        val period = 16_666_666L
        val grid = FrameVsyncGrid(period)
        grid.feed(1L, 30, period)
        val vsync = grid.lastNs
        val now = vsync + 2_000_000L
        assertTrue(grid.releasable(vsync + period + period / 2, now))
        assertFalse(grid.releasable(vsync + period + period / 2 + 1_000L, now))
    }

    @Test fun `stops following when callbacks stop`() {
        val grid = FrameVsyncGrid(content90)
        assertTrue(grid.stale(0L))
        grid.onVsync(1_000L)
        assertFalse(grid.stale(1_000L + 3 * content90))
        assertTrue(grid.stale(1_000L + 4 * content90))
        assertFalse("time not advancing is ignored", grid.onVsync(1_000L))
    }
}
