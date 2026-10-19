package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostClock.Update
import org.junit.Assert.*
import org.junit.Test

class FrameHostClockTest {
    private val periodUs = 33_333L

    /** 第 i 帧 PTS = i·33.333 ms，理想在 base + PTS 到达，另加 jitter(i) 的调度延迟。 */
    private fun FrameHostClock.feedFrames(from: Int, to: Int, baseNs: Long, jitterUs: (Int) -> Long = { 0L }) {
        for (i in from until to) assertEquals(Update.ACCEPTED, feed(i * periodUs, baseNs + (i * periodUs + jitterUs(i)) * 1_000L))
    }

    @Test fun `needs a few samples before reporting a clock`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 3, 0L)
        assertNull(clock.snapshot(100_000_000L, 1L))
        clock.feedFrames(3, 4, 0L)
        assertNotNull(clock.snapshot(100_000_000L, 1L))
    }

    @Test fun `late arrivals do not drag the clock and jitter becomes the uncertainty`() {
        val clock = FrameHostClock()
        // 交替 0 / 3 ms 的调度延迟：最早到达的样本即真实映射。
        clock.feedFrames(0, 32, 5_000_000_000L) { if (it % 2 == 0) 0L else 3_000L }
        val now = 5_000_000_000L + 1_100_000_000L
        val snapshot = clock.snapshot(now, 7L)!!
        assertEquals(1_100_000L, snapshot.mediaUs)
        assertEquals(7L, snapshot.discontinuity)
        assertEquals(1.0, snapshot.speed, 0.0)
        assertTrue(snapshot.uncertaintyUs in 500L..3_000L)
    }

    @Test fun `a better sample moves the estimate gradually and a large offset jumps at once`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 8, 0L) { 3_000L }
        val first = clock.snapshot(300_000_000L, 1L)!!
        // 一帧准时到达：下包络提前 3 ms，但每次快照最多移动 0.2 ms。
        clock.feed(8 * periodUs, 8 * periodUs * 1_000L)
        val second = clock.snapshot(300_000_000L, 1L)!!
        assertEquals(200L, second.mediaUs - first.mediaUs)
        assertTrue(second.uncertaintyUs >= 2_800L)
        val slow = FrameHostClock(jumpUs = 2_000L)
        slow.feedFrames(0, 8, 0L) { 3_000L }
        val before = slow.snapshot(300_000_000L, 1L)!!
        slow.feed(8 * periodUs, 8 * periodUs * 1_000L)
        assertEquals(3_000L, slow.snapshot(300_000_000L, 1L)!!.mediaUs - before.mediaUs)
    }

    @Test fun `seek back, jump forward and resume after pause are discontinuities`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 10, 0L)
        assertEquals(Update.DISCONTINUITY, clock.feed(2 * periodUs, 400_000_000L))
        clock.feedFrames(3, 10, 400_000_000L - 2 * periodUs * 1_000L)
        // 同一 PTS 序列继续，但墙钟停了 2 s（暂停后恢复）。
        assertEquals(Update.DISCONTINUITY, clock.feed(10 * periodUs, 400_000_000L + 10 * periodUs * 1_000L + 2_000_000_000L))
        assertNull(clock.snapshot(3_000_000_000L, 1L))
    }

    @Test fun `speed change restarts and the clock advances at the given speed`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 8, 0L)
        val doubled = (8 until 16).map { clock.feed(it * periodUs, 8 * periodUs * 1_000L + (it - 8) * periodUs * 500L, 2.0) }
        assertEquals(Update.DISCONTINUITY, doubled.first())
        assertTrue(doubled.drop(1).all { it == Update.ACCEPTED })
        val lastArrival = 8 * periodUs * 1_000L + 7 * periodUs * 500L
        val snapshot = clock.snapshot(lastArrival + 10_000_000L, 1L)!!
        assertEquals(15 * periodUs + 20_000L, snapshot.mediaUs)
        assertEquals(2.0, snapshot.speed, 0.0)
    }

    @Test fun `invalid input and time going backwards are rejected`() {
        val clock = FrameHostClock()
        assertEquals(Update.REJECTED, clock.feed(-1L, 0L))
        assertEquals(Update.REJECTED, clock.feed(0L, 0L, 0.0))
        clock.feedFrames(0, 5, 1_000_000_000L)
        assertEquals(Update.REJECTED, clock.feed(5 * periodUs, 0L))
        assertNull(clock.snapshot(0L, 1L))
    }
}
