package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Decision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Epoch
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Frame
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Mode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Reason
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Test

class FrameTimelineTest {
    private val epoch = Epoch(1L, 1L, 1L, 1L, 1L)
    private fun clock(mediaUs: Long = 0L, monotonicNs: Long = 0L, speed: Double = 1.0,
                      paused: Boolean = false, discontinuity: Long = epoch.discontinuity, uncertaintyUs: Long = 0L) =
        ClockSnapshot(mediaUs, monotonicNs, speed, paused, discontinuity, uncertaintyUs)
    private fun ready(timeline: FrameTimeline, lease: FrameTimeline.Lease, nowNs: Long): FrameTimeline.Proposal {
        val result = timeline.plan(lease, nowNs)
        assertTrue("Expected a proposal, got $result", result is Decision.Ready)
        return (result as Decision.Ready).proposal
    }
    private fun waiting(timeline: FrameTimeline, lease: FrameTimeline.Lease, nowNs: Long): Reason {
        val result = timeline.plan(lease, nowNs)
        assertTrue("Expected a wait, got $result", result is Decision.Waiting)
        return (result as Decision.Waiting).reason
    }

    @Test fun `disabled target and missing verified clock never generate an output proposal`() {
        val disabled = FrameTimeline(0)
        val off = disabled.begin(epoch).lease
        assertEquals(ClockState.READY, disabled.updateClock(off, clock()).state)
        assertEquals(listOf(11L), disabled.offer(off, Frame(11L, 0L)).discardedIds)
        assertEquals(Reason.DISABLED, waiting(disabled, off, 0L))

        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        assertTrue(timeline.offer(lease, Frame(1L, 0L)).accepted)
        assertEquals(Reason.NO_CLOCK, waiting(timeline, lease, 0L))
        val original = readyAfterClock(timeline, lease)
        assertTrue(original.isOriginal)
        assertEquals(0.0, original.alpha, 0.0)
    }

    private fun readyAfterClock(timeline: FrameTimeline, lease: FrameTimeline.Lease): FrameTimeline.Proposal {
        assertEquals(ClockState.READY, timeline.updateClock(lease, clock()).state)
        return ready(timeline, lease, 0L)
    }

    @Test fun `24 to 45 uses different actual PTS pairs and arbitrary alpha`() {
        val timeline = FrameTimeline(45)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        listOf(0L, 41_667L, 83_333L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))

        val first = ready(timeline, lease, 22_222_222L)
        assertFalse(first.isOriginal)
        assertEquals(22_222L, first.mediaUs)
        assertEquals(0L, first.before.id)
        assertEquals(1L, first.after.id)
        assertEquals(0.5333, first.alpha, 0.0001)
        assertTrue(timeline.commit(first, first.dueNs))
        val second = ready(timeline, lease, 44_444_444L)
        assertEquals(1L, second.before.id)
        assertEquals(2L, second.after.id)
        assertEquals(0.0666, second.alpha, 0.0001)
    }

    @Test fun `all target cadences keep nanosecond remainder without long term drift`() {
        for (fps in listOf(15, 30, 45, 60, 120, 144, 165, 185)) {
            val timeline = FrameTimeline(fps)
            val lease = timeline.begin(epoch).lease
            for (index in 0..fps * 4) {
                val due = index.toLong() * 1_000_000_000L / fps
                val media = (due / 1_000.0).roundToLong()
                timeline.updateClock(lease, clock(media, due))
                assertTrue(timeline.offer(lease, Frame(index.toLong(), media)).accepted)
                val proposal = ready(timeline, lease, due)
                assertEquals(due, proposal.dueNs)
                assertTrue(proposal.isOriginal)
                assertTrue(timeline.commit(proposal, due))
            }
        }
    }

    @Test fun `low frame sources reserve motion interpolation at every higher target slot`() {
        for (fps in listOf(120, 144, 165, 185)) {
            for (sourceIntervalUs in listOf(33_333L, 41_667L)) {
                val timeline = FrameTimeline(fps)
                val lease = timeline.begin(epoch).lease
                timeline.updateClock(lease, clock())
                timeline.offer(lease, Frame(1L, 0L))
                timeline.offer(lease, Frame(2L, sourceIntervalUs))
                assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
                var tick = 1L
                while ((tick * 1_000_000_000L / fps / 1_000.0).roundToLong() < sourceIntervalUs) {
                    val due = tick * 1_000_000_000L / fps
                    val proposal = ready(timeline, lease, due)
                    assertEquals(due, proposal.dueNs)
                    assertEquals(Mode.INTERPOLATE, proposal.mode)
                    assertEquals(1L, proposal.before.id)
                    assertEquals(2L, proposal.after.id)
                    assertEquals((due / 1_000.0).roundToLong().toDouble() / sourceIntervalUs,
                        proposal.alpha, 0.0000001)
                    assertTrue(timeline.commit(proposal, due))
                    tick++
                }
                assertEquals(Reason.NO_FUTURE_FRAME,
                    waiting(timeline, lease, sourceIntervalUs * 1_000L + 1_000L))
            }
        }
    }

    @Test fun `real time target respects double speed instead of doubling target FPS`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(5_000L, speed = 2.0))
        listOf(0L, 33_333L, 66_667L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
        val proposal = ready(timeline, lease, 16_666_666L)
        assertEquals(16_666_666L, proposal.dueNs)
        assertEquals(38_333L, proposal.mediaUs)
        assertTrue(proposal.isOriginal)
        assertEquals(Mode.ORIGINAL, proposal.mode)
        assertEquals(1L, proposal.before.id)
    }

    @Test fun `VFR uses enclosing PTS instead of inferring a frame number`() {
        val timeline = FrameTimeline(30)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        listOf(0L, 40_000L, 100_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 66_666_666L)
        assertEquals(1L, proposal.before.id)
        assertEquals(2L, proposal.after.id)
        assertEquals(0.44445, proposal.alpha, 0.00001)
        assertEquals(Mode.INTERPOLATE, proposal.mode)
    }

    @Test fun `high frame source selects nearest originals at 15 and 30 without model work`() {
        for (fps in listOf(15, 30)) {
            val timeline = FrameTimeline(fps)
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(5_000L))
            listOf(0L, 16_667L, 33_333L, 50_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
            assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
            if (fps == 15) {
                timeline.offer(lease, Frame(4L, 66_667L)); timeline.offer(lease, Frame(5L, 83_333L))
            }
            val due = 1_000_000_000L / fps
            val proposal = ready(timeline, lease, due)
            assertEquals(Mode.ORIGINAL, proposal.mode)
            assertEquals(proposal.before, proposal.after)
            assertEquals(0.0, proposal.alpha, 0.0)
            assertEquals(due, proposal.dueNs)
            assertEquals(if (fps == 15) 66_667L else 33_333L, proposal.before.ptsUs)
            assertEquals(if (fps == 15) 71_667L else 38_333L, proposal.mediaUs)
        }
    }

    @Test fun `VFR switches between source selection and interpolation using actual interval`() {
        val timeline = FrameTimeline(30)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(5_000L))
        listOf(0L, 10_000L, 20_000L, 70_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        val fast = ready(timeline, lease, 0L)
        assertEquals(Mode.ORIGINAL, fast.mode)
        assertEquals(0L, fast.before.ptsUs) // 距离相同，保留前帧。
        timeline.commit(fast, 0L)
        val slow = ready(timeline, lease, 33_333_333L)
        assertEquals(Mode.INTERPOLATE, slow.mode)
        assertEquals(20_000L, slow.before.ptsUs)
        assertEquals(70_000L, slow.after.ptsUs)
        assertEquals(0.36666, slow.alpha, 0.00001)
        timeline.commit(slow, slow.dueNs)
        timeline.offer(lease, Frame(4L, 90_000L))
        val fastAgain = ready(timeline, lease, 66_666_666L)
        assertEquals(Mode.ORIGINAL, fastAgain.mode)
        assertEquals(70_000L, fastAgain.before.ptsUs)
    }

    @Test fun `missing adjacent frame waits without repeating or fabricating a source frame`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 33_333L))
        assertEquals(Reason.NO_PREVIOUS_FRAME, waiting(timeline, lease, 0L))
        timeline.begin(epoch).also { fresh ->
            timeline.updateClock(fresh.lease, clock())
            timeline.offer(fresh.lease, Frame(2L, 0L))
            timeline.commit(ready(timeline, fresh.lease, 0L), 0L)
            assertEquals(Reason.NO_FUTURE_FRAME, waiting(timeline, fresh.lease, 16_666_666L))
            timeline.offer(fresh.lease, Frame(3L, 33_333L))
            assertFalse(ready(timeline, fresh.lease, 16_666_666L).isOriginal)
        }
    }

    @Test fun `single pending proposal cannot schedule duplicates and expired slots are skipped`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 100_000L))
        val old = ready(timeline, lease, 0L)
        assertEquals(Reason.IN_FLIGHT, waiting(timeline, lease, 0L))
        val fresh = ready(timeline, lease, 50_000_000L)
        assertEquals(50_000_000L, fresh.dueNs)
        assertEquals(0.5, fresh.alpha, 0.0)
        assertFalse(timeline.valid(old, 50_000_000L))
        assertFalse(timeline.commit(old, 50_000_000L))
        assertTrue(timeline.valid(fresh, 50_000_000L))
        assertTrue(timeline.commit(fresh, 50_000_000L))
    }

    @Test fun `output preparation never runs more than one target period ahead`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 100_000L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val next = ready(timeline, lease, 0L)
        assertEquals(16_666_666L, next.dueNs)
        assertTrue(timeline.commit(next, 0L))
        assertEquals(Reason.AHEAD_OF_WINDOW, waiting(timeline, lease, 0L))
    }

    @Test fun `fresh clock revision invalidates in flight work without losing frame ownership`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val old = ready(timeline, lease, 0L)
        val update = timeline.updateClock(lease, clock(2_000L, 1_000_000L))
        assertEquals(ClockState.READY, update.state)
        assertTrue(update.discardedIds.isEmpty())
        assertFalse(timeline.valid(old, 1_000_000L))
        val fresh = ready(timeline, lease, 1_000_000L)
        assertEquals(old.dueNs, fresh.dueNs)
        assertEquals(listOf(1L, 2L), timeline.close(lease))
    }

    @Test fun `consistent frequent master clock updates preserve useful in flight work`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        for (millis in 1L..10L) {
            assertEquals(ClockState.READY, timeline.updateClock(lease, clock(millis * 1_000L, millis * 1_000_000L)).state)
            assertTrue(timeline.valid(proposal, millis * 1_000_000L))
        }
        assertTrue(timeline.commit(proposal, 10_000_000L))
    }

    @Test fun `bounded millisecond clock quantization does not starve in flight interpolation`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, uncertaintyUs = 1_000L))
        timeline.offer(lease, Frame(1L, 100_000L)); timeline.offer(lease, Frame(2L, 133_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        assertEquals(1_000L, proposal.uncertaintyUs)
        listOf(400L, -300L, 900L, -800L, 100L, -900L, 700L, -600L, 200L, -100L).forEachIndexed { index, jitter ->
            val millis = index + 1L
            assertEquals(ClockState.READY, timeline.updateClock(lease,
                clock(100_000L + millis * 1_000L + jitter, millis * 1_000_000L, uncertaintyUs = 1_000L)).state)
            assertTrue(timeline.valid(proposal, millis * 1_000_000L))
        }
        assertTrue(timeline.commit(proposal, 10_000_000L))
    }

    @Test fun `clock error budget is compared to original proposal and never accumulates drift`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, uncertaintyUs = 1_000L))
        timeline.offer(lease, Frame(1L, 100_000L)); timeline.offer(lease, Frame(2L, 133_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        for (index in 1L..4L) {
            timeline.updateClock(lease, clock(100_000L + index * 1_500L, index * 1_000_000L, uncertaintyUs = 1_000L))
            assertTrue(timeline.valid(proposal, index * 1_000_000L))
        }
        timeline.updateClock(lease, clock(107_500L, 5_000_000L, uncertaintyUs = 1_000L))
        assertFalse(timeline.valid(proposal, 5_000_000L))
        val fresh = ready(timeline, lease, 5_000_000L)
        assertNotEquals(proposal.mediaUs, fresh.mediaUs)
    }

    @Test fun `stale clock blocks proposals and delayed snapshots cannot revoke a fresh one`() {
        val timeline = FrameTimeline(60, maxClockAgeNs = 100_000_000L)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(1_000L, 1_000_000L))
        timeline.offer(lease, Frame(1L, 1_000L)); timeline.offer(lease, Frame(2L, 100_000L))
        val proposal = ready(timeline, lease, 1_000_000L)
        assertEquals(ClockState.STALE_SNAPSHOT, timeline.updateClock(lease, clock()).state)
        assertTrue(timeline.valid(proposal, 1_000_000L))
        assertEquals(Reason.STALE_CLOCK, waiting(timeline, lease, 101_000_001L))
        assertFalse(timeline.valid(proposal, 101_000_001L))
        assertFalse(timeline.valid(proposal, 1_000_000L))
    }

    @Test fun `pause speed and unpause changes revoke lease and return owned frame IDs`() {
        for (changed in listOf(clock(paused = true), clock(speed = 2.0))) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.updateClock(oldLease, clock())
            timeline.offer(oldLease, Frame(1L, 0L))
            val pending = ready(timeline, oldLease, 0L)
            val update = timeline.updateClock(oldLease, changed)
            assertEquals(ClockState.RESTART_REQUIRED, update.state)
            assertEquals(listOf(1L), update.discardedIds)
            assertFalse(timeline.valid(pending, 0L))
            assertEquals(listOf(2L), timeline.offer(oldLease, Frame(2L, 33_333L)).discardedIds)
            val fresh = timeline.begin(epoch).lease
            timeline.updateClock(fresh, changed)
            timeline.offer(fresh, Frame(3L, 0L))
            if (changed.paused) {
                assertEquals(Reason.PAUSED, waiting(timeline, fresh, 0L))
                assertEquals(ClockState.RESTART_REQUIRED, timeline.updateClock(fresh, clock()).state)
            } else assertTrue(ready(timeline, fresh, 0L).isOriginal)
        }
    }

    @Test fun `all generation changes and same epoch reentry revoke old results and late frame tasks`() {
        val changed = listOf(epoch, epoch.copy(media = 2L), epoch.copy(decoder = 2L), epoch.copy(surface = 2L),
            epoch.copy(format = 2L), epoch.copy(discontinuity = 2L))
        for (nextEpoch in changed) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.updateClock(oldLease, clock())
            timeline.offer(oldLease, Frame(1L, 0L))
            val old = ready(timeline, oldLease, 0L)
            val next = timeline.begin(nextEpoch)
            assertNotSame(oldLease, next.lease)
            assertEquals(listOf(1L), next.discardedIds)
            assertFalse(timeline.valid(old, 0L))
            assertEquals(listOf(2L), timeline.offer(oldLease, Frame(2L, 0L)).discardedIds)
            timeline.updateClock(next.lease, clock(discontinuity = nextEpoch.discontinuity))
            timeline.offer(next.lease, Frame(3L, 0L))
            val fresh = ready(timeline, next.lease, 0L)
            assertFalse(timeline.commit(old, 0L))
            assertTrue(timeline.valid(fresh, 0L))
        }
    }

    @Test fun `changing target or disabling releases inputs and cannot revive an old proposal`() {
        for (fps in listOf(45, 120, 144, 165, 185)) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.offer(oldLease, Frame(1L, 0L))
            val old = readyAfterClock(timeline, oldLease)
            val next = timeline.begin(epoch, targetFps = fps)
            assertEquals(listOf(1L), next.discardedIds)
            assertFalse(timeline.valid(old, 0L))
            timeline.updateClock(next.lease, clock())
            timeline.offer(next.lease, Frame(2L, 0L))
            assertTrue(timeline.commit(ready(timeline, next.lease, 0L), 0L))
            timeline.offer(next.lease, Frame(3L, 100_000L))
            assertEquals(1_000_000_000L / fps, ready(timeline, next.lease, 0L).dueNs)
            val off = timeline.begin(epoch, targetFps = 0)
            assertEquals(listOf(2L, 3L), off.discardedIds)
            assertEquals(Reason.DISABLED, waiting(timeline, off.lease, 0L))
        }
    }

    @Test fun `leases are bound to timeline identity even with equal epoch values`() {
        val first = FrameTimeline(60)
        val second = FrameTimeline(60)
        val firstLease = first.begin(epoch).lease
        val secondLease = second.begin(epoch).lease
        second.updateClock(secondLease, clock())
        second.offer(secondLease, Frame(1L, 0L))
        assertEquals(Reason.STALE_LEASE, waiting(second, firstLease, 0L))
        assertEquals(ClockState.STALE_LEASE, second.updateClock(firstLease, clock(paused = true)).state)
        assertTrue(second.close(firstLease).isEmpty())
        assertTrue(ready(second, secondLease, 0L).isOriginal)
    }

    @Test fun `bounded eviction replacement and close expose every transferred frame ID`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        for (id in 1L..4L) assertTrue(timeline.offer(lease, Frame(id, (id - 1L) * 10_000L)).discardedIds.isEmpty())
        val old = ready(timeline, lease, 0L)
        assertEquals(listOf(1L), timeline.offer(lease, Frame(5L, 40_000L)).discardedIds)
        assertFalse(timeline.valid(old, 0L))
        assertEquals(listOf(5L), timeline.offer(lease, Frame(6L, 40_000L)).discardedIds)
        assertFalse(timeline.offer(lease, Frame(6L, 50_000L)).accepted)
        assertTrue(timeline.offer(lease, Frame(6L, 50_000L)).discardedIds.isEmpty())
        assertEquals(listOf(2L, 3L, 4L, 6L), timeline.close(lease))
        assertTrue(timeline.close(lease).isEmpty())
    }

    @Test fun `replacement revokes pending input while rejected out of order frame leaves it intact`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val old = ready(timeline, lease, 0L)
        assertEquals(listOf(3L), timeline.offer(lease, Frame(3L, 10_000L)).discardedIds)
        assertTrue(timeline.valid(old, 0L))
        assertEquals(listOf(2L), timeline.offer(lease, Frame(4L, 33_333L)).discardedIds)
        assertFalse(timeline.valid(old, 0L))
        assertEquals(4L, ready(timeline, lease, 0L).after.id)
    }

    @Test fun `unknown invalid or discontinuous clock releases frames and requires a fresh lease`() {
        for (snapshot in listOf(null, clock(mediaUs = -1L), clock(speed = Double.NaN),
            clock(speed = Double.POSITIVE_INFINITY), clock(speed = -1.0), clock(speed = 0.0),
            clock(discontinuity = 2L), clock(uncertaintyUs = -1L), clock(uncertaintyUs = 2_001L))) {
            val timeline = FrameTimeline(60)
            val lease = timeline.begin(epoch).lease
            timeline.offer(lease, Frame(1L, 0L))
            val pending = readyAfterClock(timeline, lease)
            val update = timeline.updateClock(lease, snapshot)
            assertEquals(listOf(1L), update.discardedIds)
            assertFalse(timeline.valid(pending, 0L))
            assertEquals(ClockState.STALE_LEASE, timeline.updateClock(lease, clock()).state)
        }
    }

    @Test fun `unflagged backward media clock cannot interpolate across a seek`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, 0L))
        timeline.offer(lease, Frame(1L, 100_000L))
        val pending = ready(timeline, lease, 0L)
        assertEquals(ClockState.RESTART_REQUIRED, timeline.updateClock(lease, clock(50_000L, 1_000L)).state)
        assertFalse(timeline.valid(pending, 0L))
        assertEquals(Reason.STALE_LEASE, waiting(timeline, lease, 1_000L))
    }

    @Test fun `deadline and future clock checks prevent expired or time reversed commits`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(0L, 1_000L))
        timeline.offer(lease, Frame(1L, 0L))
        assertEquals(Reason.INVALID_TIME, waiting(timeline, lease, 999L))
        val proposal = ready(timeline, lease, 1_000L)
        assertFalse(timeline.valid(proposal, 999L))
        assertFalse(timeline.commit(proposal, 1_001L))
        assertFalse(timeline.commit(proposal, 1_000L))
    }

    @Test fun `timestamp overflow or unrepresentable media mapping never creates a proposal`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(0L, Long.MAX_VALUE - 1L))
        timeline.offer(lease, Frame(1L, 0L))
        timeline.commit(ready(timeline, lease, Long.MAX_VALUE - 1L), Long.MAX_VALUE - 1L)
        assertEquals(Reason.INVALID_TIME, waiting(timeline, lease, Long.MAX_VALUE - 1L))

        val fresh = timeline.begin(epoch).lease
        timeline.updateClock(fresh, clock(speed = Double.MAX_VALUE))
        timeline.offer(fresh, Frame(2L, 0L))
        timeline.commit(ready(timeline, fresh, 0L), 0L)
        assertEquals(Reason.INVALID_TIME, waiting(timeline, fresh, 16_666_666L))
    }

    @Test fun `unsupported target and nonpositive clock age are rejected before use`() {
        for (fps in listOf(-1, 24, 119, 121, 143, 145, 164, 166, 184, 186, 240)) {
            try { FrameTimeline(fps); fail("Unsupported target") } catch (_: IllegalArgumentException) { }
        }
        try { FrameTimeline(60, 0L); fail("Invalid clock lifetime") } catch (_: IllegalArgumentException) { }
    }
}
