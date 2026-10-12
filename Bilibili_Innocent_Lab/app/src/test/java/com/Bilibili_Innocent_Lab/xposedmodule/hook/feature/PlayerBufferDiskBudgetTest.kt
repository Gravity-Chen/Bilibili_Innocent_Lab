package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PlayerBufferDiskBudgetTest {
    @Test fun `requests do not read space and repeated callbacks keep one queued sample`() {
        val fixture = Fixture()
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertEquals(1, fixture.jobs.size)
        assertTrue(fixture.directories.isEmpty())
        assertNull(fixture.budget.available(PATH_A))
        fixture.runOne()
        assertEquals(listOf(File(PATH_A).parent), fixture.directories)
        assertEquals(1_024L * MIB, fixture.budget.available(PATH_A))
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertTrue(fixture.jobs.isEmpty())
        assertEquals(1, fixture.directories.size)
    }

    @Test fun `free space is halved with reserve and low space never creates a negative budget`() {
        val fixture = Fixture()
        for ((free, expected) in listOf(
            1_024L * MIB to 384L * MIB,
            512L * MIB to 128L * MIB,
            256L * MIB to 0L,
            1L to 0L,
            0L to 0L
        )) {
            fixture.free = free
            fixture.budget.request("/allocated/cache/$free.cache")
            fixture.runOne()
            assertEquals(expected, fixture.budget.available("/allocated/cache/$free.cache"))
        }
    }

    @Test fun `long maximum space cannot overflow and configured limit remains authoritative`() {
        val fixture = Fixture(configuredBytes = Long.MAX_VALUE)
        fixture.free = Long.MAX_VALUE
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(Long.MAX_VALUE / 2L - 128L * MIB, fixture.budget.available(PATH_A))
        val capped = Fixture(configuredBytes = 256L * MIB)
        capped.free = Long.MAX_VALUE
        capped.budget.request(PATH_A)
        capped.runOne()
        assertEquals(256L * MIB, capped.budget.available(PATH_A))
    }

    @Test fun `stale and future samples are unavailable until a new queued measurement`() {
        val fixture = Fixture()
        fixture.budget.request(PATH_A)
        fixture.runOne()
        fixture.time = 31_000L
        assertNotNull(fixture.budget.available(PATH_A))
        fixture.time++
        assertNull(fixture.budget.available(PATH_A))
        fixture.budget.request(PATH_A)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
        fixture.time--
        assertNull(fixture.budget.available(PATH_A))
    }

    @Test fun `failed filesystem sample fails open and backs off until its freshness window ends`() {
        val fixture = Fixture()
        fixture.read = { throw IllegalStateException("filesystem unavailable") }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertNull(fixture.budget.available(PATH_A))
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertTrue(fixture.jobs.isEmpty())
        fixture.time = 31_001L
        fixture.read = { 1_024L * MIB }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(384L * MIB, fixture.budget.available(PATH_A))
        fixture.time += 30_001L
        fixture.read = { -1L }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertNull(fixture.budget.available(PATH_A))
    }

    @Test fun `switches before execution coalesce to the latest path and do not reuse another snapshot`() {
        val fixture = Fixture()
        fixture.budget.request(PATH_A)
        fixture.budget.request(PATH_B)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertEquals(listOf(File(PATH_B).parent), fixture.directories)
        assertNull(fixture.budget.available(PATH_A))
        assertNotNull(fixture.budget.available(PATH_B))
        fixture.budget.request(PATH_A)
        assertNull(fixture.budget.available(PATH_A))
        assertNull(fixture.budget.available(PATH_B))
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
    }

    @Test fun `path change during measurement schedules just one successor and cannot publish old path budget`() {
        val fixture = Fixture()
        fixture.read = {
            repeat(1_000) { fixture.budget.request(PATH_B) }
            assertTrue(fixture.jobs.isEmpty())
            4_096L * MIB
        }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(1, fixture.jobs.size)
        assertNull(fixture.budget.available(PATH_A))
        assertNull(fixture.budget.available(PATH_B))
        fixture.read = { 1_024L * MIB }
        fixture.runOne()
        assertEquals(384L * MIB, fixture.budget.available(PATH_B))
        assertTrue(fixture.jobs.isEmpty())
    }

    @Test fun `scheduler rejection or exception releases the in-flight claim for a later request`() {
        val fixture = Fixture()
        fixture.accept = false
        fixture.budget.request(PATH_A)
        assertTrue(fixture.jobs.isEmpty())
        assertNull(fixture.budget.available(PATH_A))
        fixture.accept = true
        fixture.schedulerThrows = true
        fixture.budget.request(PATH_A)
        assertNull(fixture.budget.available(PATH_A))
        fixture.schedulerThrows = false
        fixture.budget.request(PATH_A)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
    }

    @Test fun `invalid configured budgets and paths never produce usable space`() {
        for (configured in listOf(-1L, 0L)) {
            val fixture = Fixture(configured)
            fixture.budget.request(PATH_A)
            assertTrue(fixture.jobs.isEmpty())
            assertNull(fixture.budget.available(PATH_A))
        }
        val fixture = Fixture()
        fixture.budget.request(" ")
        assertTrue(fixture.jobs.isEmpty())
        fixture.budget.request("filename-without-parent")
        fixture.runOne()
        assertTrue(fixture.directories.isEmpty())
        assertNull(fixture.budget.available("filename-without-parent"))
    }

    @Test fun `concurrent callers cannot create duplicate queued jobs`() {
        val queued = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val budget = PlayerBufferDiskBudget(1_024L * MIB, schedule = {
            queued.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            true
        }, availableBytes = { error("sampling must remain queued") }, nowMs = { 1_000L })
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching { budget.request(PATH_A) }.onFailure(failure::set)
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1_000) { budget.request(PATH_B) }
            assertEquals(1, queued.get())
            assertNull(budget.available(PATH_B))
        } finally {
            release.countDown()
            worker.join(5_000L)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, queued.get())
    }

    private class Fixture(configuredBytes: Long = 1_024L * MIB) {
        var time = 1_000L
        var free = 4_096L * MIB
        var accept = true
        var schedulerThrows = false
        var read: (String) -> Long = { free }
        val jobs = ArrayDeque<() -> Unit>()
        val directories = mutableListOf<String>()
        val budget = PlayerBufferDiskBudget(configuredBytes, schedule = { work ->
            if (schedulerThrows) throw IllegalStateException("scheduler unavailable")
            if (accept) jobs.addLast(work)
            accept
        }, availableBytes = { path ->
            directories += path
            read(path)
        }, nowMs = { time })

        fun runOne() = jobs.removeFirst().invoke()
    }

    companion object {
        private const val MIB = 1_048_576L
        private const val PATH_A = "/allocated/a/video.cache"
        private const val PATH_B = "/allocated/b/video.cache"
    }
}
