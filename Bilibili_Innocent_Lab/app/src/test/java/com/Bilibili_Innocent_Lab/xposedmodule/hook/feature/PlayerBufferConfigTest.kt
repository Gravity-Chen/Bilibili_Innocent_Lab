package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerBufferConfigTest {
    @Test fun `unknown modes preserve host behavior`() {
        assertEquals(PlayerBufferMode.FOLLOW_HOST, PlayerBufferMode.fromValue(-1))
        assertEquals(PlayerBufferMode.FOLLOW_HOST, PlayerBufferMode.fromValue(Int.MAX_VALUE))
        assertEquals(setOf(0, 1, 2, 3), PlayerBufferConfig.supportedModes)
    }

    @Test fun `native units use long and reject corrupt persisted values`() {
        assertEquals(600_000L, PlayerBufferConfig.secondsToMillis(600))
        assertEquals(268_435_456L, PlayerBufferConfig.memoryBytes(256))
        assertEquals(8_589_934_592L, PlayerBufferConfig.diskBytes(8_192))
        assertNull(PlayerBufferConfig.secondsToMillis(0))
        assertNull(PlayerBufferConfig.secondsToMillis(Int.MAX_VALUE))
        assertNull(PlayerBufferConfig.memoryBytes(15))
        assertNull(PlayerBufferConfig.diskBytes(Int.MAX_VALUE))
    }

    @Test fun `ui normalization returns reviewed defaults rather than extreme clamps`() {
        assertEquals(30, PlayerBufferConfig.normalizeSeconds(-1))
        assertEquals(64, PlayerBufferConfig.normalizeMemoryMb(Int.MAX_VALUE))
        assertEquals(1_024, PlayerBufferConfig.normalizeDiskMb(0))
        assertEquals(1, PlayerBufferConfig.normalizeSeconds(1))
        assertEquals(256, PlayerBufferConfig.normalizeDiskMb(256))
    }
}
