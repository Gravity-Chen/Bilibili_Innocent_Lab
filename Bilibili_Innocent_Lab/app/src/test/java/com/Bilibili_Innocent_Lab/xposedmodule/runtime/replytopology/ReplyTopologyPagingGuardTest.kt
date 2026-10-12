package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologyPagingGuardTest {

    @Test
    fun emptyNextOffsetCompletesEvenAtNodeBudget() {
        val guard = ReplyTopologyPagingGuard(
            startedAtMs = 100L,
            budget = ReplyTopologyPagingBudget(maxUniqueNodes = 10)
        )

        val decision = guard.onPage("first", "", 10, 10, 101L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.COMPLETE, decision.reason)
        assertTrue(decision.complete)
    }

    @Test
    fun repeatedOffsetStopsWithoutRequestingLoop() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        assertEquals(
            ReplyTopologyPagingDecision.LoadNext("second"),
            guard.onPage("first", "second", 3, 3, 1L)
        )

        val decision = guard.onPage("second", "first", 2, 5, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.REPEATED_OFFSET, decision.reason)
        assertFalse(decision.complete)
    }

    @Test
    fun twoPagesWithoutNewRpidStopAsNoProgress() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", 0, 10, 1L)

        val decision = guard.onPage("b", "c", 0, 10, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.NO_PROGRESS, decision.reason)
    }

    @Test
    fun resourceBudgetsStopPartialLoading() {
        val nodeGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxUniqueNodes = 3)
        )
        val nodeStop = nodeGuard.onPage("a", "b", 3, 3, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT, nodeStop.reason)

        val pageGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxPages = 1)
        )
        val pageStop = pageGuard.onPage("a", "b", 1, 1, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.PAGE_LIMIT, pageStop.reason)

        val timeGuard = ReplyTopologyPagingGuard(
            100L,
            ReplyTopologyPagingBudget(maxElapsedMs = 10L)
        )
        val timeStop = timeGuard.onPage("a", "b", 1, 1, 110L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.TIME_LIMIT, timeStop.reason)
    }

    @Test
    fun invalidCountersFailClosedForThisFeatureOnly() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 100L)
        val decision = guard.onPage("a", "b", -1, 0, 99L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
        assertFalse(decision.complete)
    }

    @Test
    fun decreasingUniqueTotalIsRejectedAsStaleAccumulatorState() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", newUniqueNodes = 4, totalUniqueNodes = 4, nowMs = 1L)

        val decision = guard.onPage(
            "b",
            "c",
            newUniqueNodes = 1,
            totalUniqueNodes = 3,
            nowMs = 2L
        ) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
    }

    @Test fun continuedBatchesKeepCursorHistoryAndUseNewNodeBudgetPerBatch() {
        val budget = ReplyTopologyPagingBudget(maxUniqueNodes = 2)
        val guard = ReplyTopologyPagingGuard(0L, budget)
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT,
            (guard.onPage("", "a", 2, 2, 1L) as ReplyTopologyPagingDecision.Stop).reason)
        guard.beginBatch(2L, budget)
        assertEquals(ReplyTopologyPagingDecision.LoadNext("b"), guard.onPage("a", "b", 1, 3, 3L))
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT,
            (guard.onPage("b", "c", 1, 4, 4L) as ReplyTopologyPagingDecision.Stop).reason)
        guard.beginBatch(5L, budget)
        val loop = guard.onPage("c", "a", 1, 5, 6L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.REPEATED_OFFSET, loop.reason)
        assertFalse(loop.complete)
    }

    @Test fun terminalCursorNeverCompletesATruncatedPage() {
        val guard = ReplyTopologyPagingGuard(0L)
        val stop = guard.onPage("", null, 4, 4, 1L, pageTruncated = true) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, stop.reason)
        assertFalse(stop.complete)
    }

    @Test fun batchingCannotResetTheNoProgressProtection() {
        val guard = ReplyTopologyPagingGuard(0L)
        guard.onPage("", "a", 1, 1, 1L)
        guard.onPage("a", "b", 0, 1, 2L)
        guard.beginBatch(3L, ReplyTopologyPagingBudget())
        val stop = guard.onPage("b", "c", 0, 1, 4L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.NO_PROGRESS, stop.reason)
    }

    @Test fun manyBatchesCanFinishBeyondTheOldFiveThousandNodeLimit() {
        val budget = ReplyTopologyPagingBudget(maxUniqueNodes = 1_500)
        val guard = ReplyTopologyPagingGuard(0L, budget)
        for (page in 0..5) {
            val now = page * 2L + 1L
            val stop = guard.onPage(if (page == 0) "" else "p$page",
                if (page == 5) null else "p${page + 1}", 1_500, (page + 1) * 1_500, now) as ReplyTopologyPagingDecision.Stop
            assertEquals(page == 5, stop.complete)
            if (page < 5) guard.beginBatch(now + 1L, budget)
        }
    }

    @Test fun openingTheFullTreeDuringLoadingUsesItsExtendedNoProgressBudget() {
        val guard = ReplyTopologyPagingGuard(0L)
        guard.onPage("", "a", 1, 1, 1L)
        guard.onPage("a", "b", 0, 1, 2L)
        guard.beginBatch(3L, ReplyTopologyPagingBudget(maxConsecutiveNoProgressPages = 3))
        assertEquals(ReplyTopologyPagingDecision.LoadNext("c"), guard.onPage("b", "c", 0, 1, 4L))
        assertEquals(ReplyTopologyPagingDecision.LoadNext("d"), guard.onPage("c", "d", 1, 2, 5L))
    }
}
