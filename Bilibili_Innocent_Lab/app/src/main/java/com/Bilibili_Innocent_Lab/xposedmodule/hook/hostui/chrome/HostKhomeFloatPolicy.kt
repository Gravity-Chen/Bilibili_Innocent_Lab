package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import kotlin.math.abs

/** Geometry for floating the khome capsule over its feed; all values in pixels. */
internal object HostKhomeFloatPolicy {
    /** Status-bar scrim: full strength over the icons, then fades out at the status bar's lower edge. */
    const val SCRIM_ALPHA = .9f
    const val SCRIM_HOLD = .6f

    /** Writes inside one second beyond this count mean the host keeps undoing the layout; stop floating. */
    const val CHURN_LIMIT = 8
    const val CHURN_WINDOW_MS = 1000L

    /**
     * AppBarLayout's ScrollingViewBehavior lays the feed out at `AppBar.bottom + topMargin` and measures
     * it taller by a negative margin. Cancelling the expanded AppBar bottom puts the feed at the
     * CoordinatorLayout top while its bottom, and the host's collapse offsets, stay unchanged.
     */
    fun contentMargin(base: Int, appBarTopMargin: Int, appBarHeight: Int): Int = base - (appBarTopMargin + appBarHeight)

    /**
     * Both SwipeRefreshLayouts (androidx and the host copy) settle at `end` with a custom start and at
     * `end - |start|` without one; a negative start means the default, non-custom pair. Shifting into
     * the custom form keeps the native drag distance and resting gap, only `shift` lower.
     */
    fun refreshOffsets(start: Int, end: Int, shift: Int): Pair<Int, Int> =
        if (start < 0) (start + shift) to (end - abs(start) + shift) else (start + shift) to (end + shift)

    /** setProgressViewOffset always marks the start as custom; write the equivalent of the original pair. */
    fun restoredRefreshOffsets(start: Int, end: Int): Pair<Int, Int> =
        if (start < 0) start to (end - abs(start)) else start to end

    /** The page's own list must span it; carousels, banners and nested lists are not the feed. */
    fun primaryList(listWidth: Int, listHeight: Int, pageWidth: Int, pageHeight: Int): Boolean =
        pageWidth > 0 && pageHeight > 0 && listWidth >= pageWidth * .8f && listHeight >= pageHeight * .5f
}
