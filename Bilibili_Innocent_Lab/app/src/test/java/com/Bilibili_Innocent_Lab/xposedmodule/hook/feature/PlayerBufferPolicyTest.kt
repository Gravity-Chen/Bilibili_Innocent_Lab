package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferPolicyTest {
    private val settings = PlayerBufferPolicy.Settings(
        mode = PlayerBufferMode.CUSTOM,
        seconds = 60,
        memoryMb = 64,
        diskMb = 1_024,
        wifiOnly = true
    )
    private val media = PlayerBufferPolicy.Media(
        current = true,
        eligible = true,
        progressive = true,
        fileSize = 128L * PlayerBufferConfig.MIB,
        durationMs = 60_000L
    )
    private val diskBytes = 512L * PlayerBufferConfig.MIB

    @Test
    fun `follow host makes no native changes even with corrupt ancillary settings`() {
        val decision = decide(settings.copy(
            mode = PlayerBufferMode.FOLLOW_HOST,
            seconds = Int.MAX_VALUE,
            memoryMb = -1,
            diskMb = Int.MIN_VALUE
        ))
        assertDisabled(decision, "follow-host")
    }

    @Test
    fun `unknown persisted modes resolve to zero native changes`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.fromValue(Int.MAX_VALUE)))
        assertDisabled(decision, "follow-host")
    }

    @Test
    fun `unconfirmed current item and unrelated routes never expand prefetch`() {
        for (mode in listOf(PlayerBufferMode.LOW, PlayerBufferMode.CUSTOM, PlayerBufferMode.WHOLE)) {
            assertDisabled(decide(settings.copy(mode = mode), media.copy(current = false)), "not-current")
            assertDisabled(decide(settings.copy(mode = mode), media.copy(eligible = false)), "not-applicable")
        }
    }

    @Test
    fun `low mode keeps one media second and divides the memory budget`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.LOW))
        assertTrue(decision.active)
        assertFalse(decision.fullPreload)
        assertEquals(1_000L, decision.itemTimeMs)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.itemBytes)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.consumerBytes)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
    }

    @Test
    fun `custom duration uses media milliseconds at both supported endpoints`() {
        assertEquals(1_000L, decide(settings.copy(seconds = 1)).itemTimeMs)
        assertEquals(600_000L, decide(settings.copy(seconds = 600)).itemTimeMs)
        assertEquals(60_000L, decide().itemTimeMs)
    }

    @Test
    fun `every supported memory budget is shared by two bounded queues`() {
        for (memoryMb in PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB) {
            val decision = decide(settings.copy(memoryMb = memoryMb))
            val total = memoryMb * PlayerBufferConfig.MIB
            assertTrue(decision.itemBytes!! > 0L)
            assertTrue(decision.consumerBytes!! > 0L)
            assertEquals(total, decision.itemBytes + decision.consumerBytes)
            assertTrue(decision.itemBytes < total)
            assertTrue(decision.consumerBytes < total)
        }
    }

    @Test
    fun `corrupt persisted numeric values fail open without partial native updates`() {
        val invalidSettings = listOf(
            settings.copy(seconds = 0), settings.copy(seconds = -1), settings.copy(seconds = Int.MAX_VALUE),
            settings.copy(memoryMb = 15), settings.copy(memoryMb = 257), settings.copy(memoryMb = Int.MIN_VALUE),
            settings.copy(diskMb = 255), settings.copy(diskMb = 8_193), settings.copy(diskMb = Int.MAX_VALUE)
        )
        for (mode in listOf(PlayerBufferMode.LOW, PlayerBufferMode.CUSTOM, PlayerBufferMode.WHOLE)) {
            invalidSettings.forEach { assertDisabled(decide(it.copy(mode = mode)), "invalid-settings") }
        }
    }

    @Test
    fun `whole prefetch keeps packet memory finite and forwards only the actual file size`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.WHOLE))
        assertTrue(decision.active)
        assertTrue(decision.fullPreload)
        assertEquals(PlayerBufferConfig.DEFAULT_SECONDS * 1_000L, decision.itemTimeMs)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.itemBytes)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.consumerBytes)
        assertEquals(media.fileSize, decision.forwardBytes)
        assertEquals(diskBytes, decision.diskCapacity)
        assertTrue(decision.itemTimeMs!! < media.durationMs)
        assertTrue(decision.itemBytes!! < decision.diskCapacity!!)
    }

    @Test
    fun `whole prefetch is capped by both measured capacity and configured capacity`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE, diskMb = 256)
        assertEquals(256L * PlayerBufferConfig.MIB, decide(whole, verifiedDiskBytes = Long.MAX_VALUE).diskCapacity)
        val measured = 200L * PlayerBufferConfig.MIB
        assertEquals(measured, decide(whole, verifiedDiskBytes = measured).diskCapacity)
    }

    @Test
    fun `unverified disk budgets retain only finite phase one settings`() {
        for (budget in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(settings.copy(mode = PlayerBufferMode.WHOLE), verifiedDiskBytes = budget), "disk-budget-unverified")
        }
    }

    @Test
    fun `DASH unknown size and unknown duration cannot become whole disk preload`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        assertWholeFallback(decide(whole, media.copy(progressive = false)), "unsupported-disk-route")
        for (size in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = size)), "unknown-media-size")
        }
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(durationMs = duration)), "unknown-media-duration")
        }
    }

    @Test
    fun `whole capacity admission reserves headroom and rejects exact capacity or overflow`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        val headroom = PlayerBufferPolicy.HEADROOM_BYTES
        val accepted = decide(whole, media.copy(fileSize = diskBytes - headroom))
        assertTrue(accepted.fullPreload)
        assertEquals(diskBytes - headroom, accepted.forwardBytes)
        for (size in listOf(diskBytes - headroom + 1L, diskBytes, Long.MAX_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = size)), "insufficient-disk-budget")
        }
        for (budget in listOf(headroom - 1L, headroom)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = 1L), budget), "insufficient-disk-budget")
        }
    }

    @Test
    fun `WiFi admission applies only to disk preload and respects explicit opt out`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        assertWholeFallback(decide(whole, wifi = false), "wifi-required")
        assertTrue(decide(whole.copy(wifiOnly = false), wifi = false).fullPreload)
        assertTrue(decide(whole, wifi = true).fullPreload)
        assertTrue(decide(settings.copy(mode = PlayerBufferMode.LOW), wifi = false).active)
        assertTrue(decide(settings, wifi = false).active)
    }

    private fun decide(
        settings: PlayerBufferPolicy.Settings = this.settings,
        media: PlayerBufferPolicy.Media = this.media,
        verifiedDiskBytes: Long? = diskBytes,
        wifi: Boolean = true
    ) = PlayerBufferPolicy.decide(settings, media, verifiedDiskBytes, wifi)

    private fun assertDisabled(decision: PlayerBufferPolicy.Decision, reason: String) {
        assertFalse(decision.active)
        assertFalse(decision.fullPreload)
        assertNull(decision.itemTimeMs)
        assertNull(decision.itemBytes)
        assertNull(decision.consumerBytes)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
        assertEquals(reason, decision.reason)
    }

    private fun assertWholeFallback(decision: PlayerBufferPolicy.Decision, reason: String) {
        assertTrue(decision.active)
        assertFalse(decision.fullPreload)
        assertEquals(PlayerBufferConfig.DEFAULT_SECONDS * 1_000L, decision.itemTimeMs)
        assertEquals(settings.memoryMb * PlayerBufferConfig.MIB, decision.itemBytes!! + decision.consumerBytes!!)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
        assertEquals(reason, decision.reason)
    }
}
