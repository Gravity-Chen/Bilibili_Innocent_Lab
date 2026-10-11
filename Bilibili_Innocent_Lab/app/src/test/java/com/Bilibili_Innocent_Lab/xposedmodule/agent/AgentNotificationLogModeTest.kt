package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationLogModeTest {
    private val current = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    private val old = "00000000-0000-4000-8000-000000000003"
    private val next = "00000000-0000-4000-8000-000000000004"

    @Test fun activeTaskCanToggleLogsAndReturnToStatusWithoutChangingTaskIdentity() {
        val display = AgentNotificationLogMode()
        display.synchronize(current)
        assertFalse(display.showing(current))
        assertTrue(display.toggle(current, current, true))
        assertTrue(display.showing(current))
        assertFalse(display.showing(other))
        assertTrue(display.toggle(current, current, true))
        assertFalse(display.showing(current))
    }

    @Test fun staleNotificationCannotToggleTheNextTaskOrEraseItsCurrentDisplay() {
        val display = AgentNotificationLogMode()
        display.synchronize(old)
        assertTrue(display.toggle(old, old, true))
        display.synchronize(next)
        assertFalse(display.showing(next))
        assertFalse(display.toggle(old, next, true))
        assertTrue(display.toggle(next, next, true))
        assertFalse(display.toggle(old, next, true))
        assertTrue(display.showing(next))
    }

    @Test fun stoppedMissingAndMismatchedOwnershipCannotEnterLogMode() {
        val display = AgentNotificationLogMode()
        assertFalse(display.toggle(current, current, true))
        display.synchronize(current)
        assertFalse(display.toggle(current, current, false))
        assertFalse(display.toggle(current, null, true))
        assertFalse(display.toggle(current, other, true))
        assertFalse(display.toggle("", "", true))
        assertFalse(display.showing(current))
    }

    @Test fun observingTheSameTaskRetainsDisplayButDestroyingItsOwnerClearsIt() {
        val display = AgentNotificationLogMode()
        display.synchronize(current)
        display.toggle(current, current, true)
        display.synchronize(current)
        assertTrue(display.showing(current))
        display.synchronize(null)
        assertFalse(display.showing(current))
        assertFalse(display.toggle(current, current, true))
    }

    @Test fun notificationSelectsOnlyTheLastSixCurrentTaskEnumEvents() {
        var now = 0L
        val log = AgentExecutionLog({ now }, { 1_000L })
        assertTrue(log.begin(current))
        repeat(20) { index ->
            now++
            assertTrue(log.record(current, AgentLogPhase.READING, AgentLogStatus.SUCCEEDED,
                source = 1, tool = AgentLogTool.DETAILS, step = index.toLong()))
        }
        val snapshot = log.snapshot()
        val selected = AgentNotificationLogContent.recent(snapshot, current)
        assertEquals(6, selected.size)
        assertEquals((14L..19L).toList(), selected.map { it.step })
        assertTrue(selected.all { it.tool == AgentLogTool.DETAILS })
        assertEquals(21, snapshot.tasks.single().events.size)
    }

    @Test fun missingCurrentTaskDoesNotFallBackToAnotherTasksExecutionLog() {
        val log = AgentExecutionLog({ 0L }, { 1_000L })
        assertTrue(log.begin(other))
        assertTrue(log.record(other, AgentLogPhase.SEARCHING, tool = AgentLogTool.SEARCH))
        assertTrue(AgentNotificationLogContent.recent(log.snapshot(), current).isEmpty())
    }

    @Test fun currentTaskSelectionDoesNotExposeEarlierTaskEvents() {
        val log = AgentExecutionLog({ 0L }, { 1_000L })
        assertTrue(log.begin(other))
        assertTrue(log.record(other, AgentLogPhase.SEARCHING, source = 2, tool = AgentLogTool.SEARCH))
        assertTrue(log.begin(current))
        assertTrue(log.record(current, AgentLogPhase.READING, source = 1, tool = AgentLogTool.DETAILS))
        val selected = AgentNotificationLogContent.recent(log.snapshot(), current)
        assertEquals(2, selected.size)
        assertEquals(AgentLogTool.DETAILS, selected.last().tool)
        assertTrue(selected.none { it.source == 2 || it.tool == AgentLogTool.SEARCH })
    }
}
