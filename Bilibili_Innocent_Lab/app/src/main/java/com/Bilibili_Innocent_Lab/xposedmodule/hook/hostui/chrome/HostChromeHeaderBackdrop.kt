package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.lang.ref.WeakReference

/**
 * khome 9.16 paints the status-bar band and the search-row base in a separate ComposeView
 * (`main_top_layout`) behind the AppBar. Once the capsule replaces the second row, that native
 * header is hidden and, where nothing else paints, the page base fills the uncovered root.
 *
 * Only values this object wrote are restored, and only while they are still the current ones:
 * the host keeps ownership of translation, later alpha changes and any background it installs.
 */
internal class HostChromeHeaderBackdrop<H : Any, R : Any>(private val ops: Ops<H, R>) {
    interface Ops<H, R> {
        fun alpha(header: H): Float
        fun setAlpha(header: H, alpha: Float)
        fun background(root: R): Any?
        fun setBackground(root: R, value: Any?)
        fun newFill(color: Int): Any
        fun recolor(fill: Any, color: Int)
    }

    private var header: WeakReference<H>? = null
    private var headerAlpha = 1f
    private var root: WeakReference<R>? = null
    private var fill: Any? = null

    val hidden: Boolean get() = header?.get() != null

    /** [base] null keeps the root transparent so a module wallpaper behind it stays visible. */
    fun apply(target: H, owner: R, base: Int?) {
        if (header?.get() !== target) restoreHeader()
        if (header?.get() == null) {
            headerAlpha = ops.alpha(target)
            header = WeakReference(target)
        }
        if (ops.alpha(target) != 0f) ops.setAlpha(target, 0f)
        if (root?.get() !== owner) restoreRoot()
        val current = ops.background(owner)
        val ours = fill
        when {
            base == null -> restoreRoot()
            ours != null && current === ours -> ops.recolor(ours, base)
            current == null -> {
                val created = ops.newFill(base)
                ops.setBackground(owner, created)
                fill = created; root = WeakReference(owner)
            }
            // The host installed its own background later; it already paints this region.
            else -> { fill = null; root = null }
        }
    }

    fun restore() { restoreHeader(); restoreRoot() }

    private fun restoreHeader() {
        val current = header?.get()
        header = null
        // A host alpha change after hiding wins over the remembered value.
        if (current != null && ops.alpha(current) == 0f) ops.setAlpha(current, headerAlpha)
        headerAlpha = 1f
    }

    private fun restoreRoot() {
        val current = root?.get()
        val ours = fill
        root = null; fill = null
        if (current != null && ours != null && ops.background(current) === ours) ops.setBackground(current, null)
    }
}
