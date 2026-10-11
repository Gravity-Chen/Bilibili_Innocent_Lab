package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentForegroundWindowPolicyTest {
    private val hostPackage = "tv.danmaku.bili"
    private val modulePackage = "com.Bilibili_Innocent_Lab.xposedmodule"
    private val host = AgentForegroundWindowPolicy.Window(1, true, true, true, hostPackage, "哔哩哔哩")
    private val island = AgentForegroundWindowPolicy.Window(2, false, true, false, modulePackage,
        AgentForegroundWindowPolicy.ISLAND_WINDOW_TITLE)

    private fun selected(vararg windows: AgentForegroundWindowPolicy.Window, showing: Boolean = true): Int? =
        AgentForegroundWindowPolicy.hostWindowId(windows.toList(), hostPackage, modulePackage, if (showing) island.id else null)

    @Test fun `normal active focused host works without overlay permission or a display`() {
        assertEquals(1, selected(host, showing = false))
    }

    @Test fun `expand collapse log scrolling and switching may activate only the owned island`() {
        assertEquals(1, selected(host.copy(active = false), island))
        assertEquals(1, selected(host, island))
        assertEquals(1, selected(host, island.copy(active = false)))
    }

    @Test fun `a title alone cannot impersonate an attached island`() {
        assertNull(selected(host.copy(active = false), island, showing = false))
        assertNull(selected(host.copy(active = false), island.copy(packageName = "other.app")))
        assertNull(selected(host.copy(active = false), island.copy(title = "另一个悬浮窗")))
        assertNull(selected(host.copy(active = false), island.copy(packageName = null)))
        assertNull(selected(host.copy(active = false), island.copy(title = null)))
    }

    @Test fun `own module settings or a log Activity does not grant host input control`() {
        assertNull(selected(host.copy(active = false), island.copy(application = true, title = "模块设置")))
        assertNull(selected(host.copy(active = false, focused = false),
            island.copy(application = true, focused = true, title = "执行日志")))
    }

    @Test fun `another app a notification panel or input method remains an interruption`() {
        for (name in listOf("com.android.systemui", "keyboard.app", "another.app")) {
            assertNull(selected(host.copy(active = false), island.copy(packageName = name)))
        }
        assertNull(selected(host.copy(active = false, focused = false), island,
            host.copy(id = 3, packageName = "another.app")))
    }

    @Test fun `an attached island cannot substitute for actual host focus`() {
        assertNull(selected(host.copy(focused = false), island))
        assertNull(selected(host.copy(application = false), island))
        assertNull(selected(island))
    }

    @Test fun `ambiguous focused application windows fail closed`() {
        assertNull(selected(host, host.copy(id = 3), island))
        assertNull(selected(host, host.copy(id = 3, packageName = "other.app")))
    }

    @Test fun `unknown active window rejects even when host and owned island remain visible`() {
        assertNull(selected(host, island, island.copy(id = 3, packageName = null, title = null)))
        assertNull(selected(host.copy(active = false)))
    }

    @Test fun `same package and title still require the actual attached window id`() {
        assertNull(selected(host.copy(active = false), island.copy(id = 3)))
        assertNull(AgentForegroundWindowPolicy.hostWindowId(listOf(host.copy(active = false), island),
            hostPackage, modulePackage, -1))
        assertNull(selected(host, island.copy(id = host.id)))
    }
}
