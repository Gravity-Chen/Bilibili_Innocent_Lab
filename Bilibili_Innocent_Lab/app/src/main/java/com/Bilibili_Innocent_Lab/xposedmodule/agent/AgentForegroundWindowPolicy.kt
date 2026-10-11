package com.Bilibili_Innocent_Lab.xposedmodule.agent

/** 触摸活动窗口可以是展示叠窗；操作权仍要求唯一的、有焦点的宿主应用窗口。 */
internal object AgentForegroundWindowPolicy {
    const val ISLAND_WINDOW_TITLE = "Agent task status"
    data class Window(val id: Int, val application: Boolean, val active: Boolean, val focused: Boolean,
                      val packageName: String?, val title: String?)

    fun hostWindowId(windows: List<Window>, hostPackage: String, modulePackage: String,
                     islandWindowId: Int?): Int? {
        if (windows.map { it.id }.distinct().size != windows.size) return null
        val host = windows.filter { it.application && it.focused }.singleOrNull()
            ?.takeIf { it.packageName == hostPackage } ?: return null
        fun ownIsland(window: Window): Boolean = islandWindowId != null && islandWindowId >= 0 &&
            window.id == islandWindowId && window.packageName == modulePackage &&
            window.title == ISLAND_WINDOW_TITLE
        val active = windows.filter { it.active }
        if (active.isEmpty() || active.any { it.id != host.id && !ownIsland(it) }) return null
        if (!host.active && active.none(::ownIsland)) return null
        return host.id
    }
}
