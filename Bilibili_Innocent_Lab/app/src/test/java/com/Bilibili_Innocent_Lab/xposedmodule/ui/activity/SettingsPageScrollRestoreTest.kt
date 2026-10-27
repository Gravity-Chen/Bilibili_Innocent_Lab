package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingsPageScrollRestoreTest {
    @Test fun firstNavigationToALazyPageKeepsItsSavedOffset() {
        val restore = SettingsPageScrollRestore(listOf(0, 0, 500, 0, 0))
        restore.userInput(0)
        assertEquals(500, restore.savedOffset(2, 0))
        val ticket = requireNotNull(restore.prepare(2))
        assertEquals(500, restore.savedOffset(2, 0))
        assertEquals(500, restore.consume(ticket))
        assertEquals(520, restore.savedOffset(2, 520))
        assertNull(restore.prepare(2))
    }

    @Test fun inputOnTheTargetPageCancelsOldRestoreAndPreservesNewScroll() {
        val restore = SettingsPageScrollRestore(listOf(300))
        val ticket = requireNotNull(restore.prepare(0))
        restore.userInput(0)
        assertNull(restore.consume(ticket))
        assertEquals(27, restore.savedOffset(0, 27))
    }

    @Test fun replacementAndCloseInvalidateLayoutTickets() {
        val restore = SettingsPageScrollRestore(listOf(100))
        val first = requireNotNull(restore.prepare(0))
        val replacement = requireNotNull(restore.prepare(0))
        assertNull(restore.consume(first))
        restore.close()
        assertNull(restore.consume(replacement))
        assertNull(restore.prepare(0))
    }
}
