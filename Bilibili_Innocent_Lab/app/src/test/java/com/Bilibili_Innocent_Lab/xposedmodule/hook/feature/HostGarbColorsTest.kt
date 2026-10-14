package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbColors
import org.junit.Assert.*
import org.junit.Test

class HostGarbColorsTest {
    @Test fun transparentPixelsAndMonochromeFramesCannotBecomeAnAccent() {
        assertNull(HostGarbColors.dominant(intArrayOf(0x0000AAFF, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())))
        assertFalse(HostGarbColors.chromatic(null))
        assertFalse(HostGarbColors.chromatic(0))
    }

    @Test fun competingArtworkColorsKeepTheDominantHueInsteadOfAveragingToGrey() {
        val blue = 0xFF3366CC.toInt()
        val red = 0xFFCC6633.toInt()
        assertEquals(blue, HostGarbColors.dominant(intArrayOf(blue, blue, red, 0xFFFFFFFF.toInt())))
    }

    @Test fun garbSurfacesUseSevenPercentTintAndRetainDayNightSemantics() {
        val accent = 0xFF6E89FF.toInt()
        for (dark in listOf(false, true)) {
            val plain = HostChromeColors(dark, accent).palette()
            val themed = HostChromeColors(dark, accent, accent).palette()
            assertEquals(HostGarbColors.blend(plain.surface, accent, .07f), themed.surface)
            assertEquals(plain.background, themed.background)
            assertEquals(plain.textPrimary, themed.textPrimary)
            assertTrue(HostGarbColors.contrast(themed.primary, themed.surface) >= 4.5)
            assertTrue(HostGarbColors.contrast(themed.onPrimary, themed.primary) >= 4.5)
        }
    }

    @Test fun darkBlueAndPaleYellowRemainReadableInBothModes() {
        for (color in listOf(0xFF050B35.toInt(), 0xFFFFF7A0.toInt(), 0xFF00A090.toInt())) {
            for (dark in listOf(false, true)) {
                val palette = HostChromeColors(dark, color, color).palette()
                assertTrue(HostGarbColors.contrast(palette.primary, palette.surface) >= 4.5)
            }
        }
    }
}
