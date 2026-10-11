package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import org.junit.Assert.*
import org.junit.Test

class HostHomeCardSpacingSpecTest {
    @Test fun defaultsAndUnsupportedLayoutsPreserveOriginalSpacing() {
        val defaults = HostHomeCardSpacingSpec()
        assertFalse(defaults.enabled)
        assertNull(defaults.horizontalMargin(0, true, 0, 0, 3f))
        assertNull(defaults.verticalMargin(0, true, 0, 3f))
        val spacing = HostHomeCardSpacingSpec(8, 12)
        assertNull(spacing.horizontalMargin(-1, true, 0, 0, 3f))
        assertNull(spacing.verticalMargin(-1, true, 0, 3f))
        assertEquals(defaults, HostHomeCardSpacingSpec(-2, 41).normalized())
    }

    @Test fun twoColumnsHaveExactGapIncludingOddPixelsAndNativeDecorations() {
        val spacing = HostHomeCardSpacingSpec(7, 9)
        val density = 2.75f
        repeat(100) {
            val rightOfFirst = spacing.horizontalMargin(0, false, 0, 12, density)!!
            val leftOfSecond = spacing.horizontalMargin(1, true, 0, 15, density)!!
            assertEquals(19, rightOfFirst + 12 + leftOfSecond + 15)
            val top = spacing.verticalMargin(0, true, 20, density)!!
            val bottom = spacing.verticalMargin(0, false, 17, density)!!
            assertEquals(24, top + 20 + bottom + 17)
            assertEquals(19, spacing.horizontalMargin(0, true, 10, 5, density)!! + 10 + 5)
            assertEquals(19, spacing.horizontalMargin(1, false, 10, 5, density)!! + 10 + 5)
        }
    }

    @Test fun zeroGapOverridesNativeWhitespaceAndAxesRemainIndependent() {
        val spacing = HostHomeCardSpacingSpec(0, 40)
        assertEquals(-30, spacing.horizontalMargin(2, true, 20, 10, 3f))
        assertEquals(60, spacing.verticalMargin(2, true, 0, 3f))
        val verticalOnly = HostHomeCardSpacingSpec(verticalDp = 10)
        assertTrue(verticalOnly.enabled)
        assertNull(verticalOnly.horizontalMargin(1, false, 10, 10, 3f))
        assertEquals(15, verticalOnly.verticalMargin(2, false, 0, 3f))
    }
}
