package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import org.junit.Assert.*
import org.junit.Test

class HostChromeHeaderBackdropTest {
    private class Header(var alpha: Float = 1f)
    private class Root(var background: Any? = null)
    private class Fill(var color: Int)

    private val backdrop = HostChromeHeaderBackdrop(object : HostChromeHeaderBackdrop.Ops<Header, Root> {
        override fun alpha(header: Header) = header.alpha
        override fun setAlpha(header: Header, alpha: Float) { header.alpha = alpha }
        override fun background(root: Root) = root.background
        override fun setBackground(root: Root, value: Any?) { root.background = value }
        override fun newFill(color: Int): Any = Fill(color)
        override fun recolor(fill: Any, color: Int) { (fill as Fill).color = color }
    })

    @Test fun `hides the native header and fills only an empty root, then restores both`() {
        val header = Header(.8f); val root = Root()
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        assertEquals(0f, header.alpha)
        assertEquals(0xFFF1F2F3.toInt(), (root.background as Fill).color)
        backdrop.apply(header, root, 0xFF0A0B0C.toInt())
        assertEquals("Theme change recolors the same fill", 0xFF0A0B0C.toInt(), (root.background as Fill).color)
        backdrop.restore()
        assertEquals(.8f, header.alpha)
        assertNull(root.background)
        assertFalse(backdrop.hidden)
    }

    @Test fun `host-owned background and later host values win`() {
        val header = Header(); val hostBackground = Any(); val root = Root(hostBackground)
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        assertSame("An existing host background already paints the region", hostBackground, root.background)
        header.alpha = .5f
        backdrop.restore()
        assertEquals("A host alpha change after hiding is not overwritten", .5f, header.alpha)
        assertSame(hostBackground, root.background)
    }

    @Test fun `wallpaper keeps the root transparent and removes an earlier fill`() {
        val header = Header(); val root = Root()
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        backdrop.apply(header, root, null)
        assertNull(root.background)
        assertEquals(0f, header.alpha)
    }

    @Test fun `a replaced header or root view is restored before the new one is taken`() {
        val first = Header(); val second = Header(); val root = Root(); val other = Root()
        backdrop.apply(first, root, 1)
        backdrop.apply(second, other, 2)
        assertEquals(1f, first.alpha)
        assertNull(root.background)
        assertEquals(0f, second.alpha)
        assertEquals(2, (other.background as Fill).color)
    }

    @Test fun `page base must agree with the resolved light or dark host theme`() {
        assertEquals(0xFFF1F2F3.toInt(), HostChromeColors.pageBase(0xFFF1F2F3.toInt(), dark = false))
        assertEquals(0xFF0A0B0C.toInt(), HostChromeColors.pageBase(0x000A0B0C, dark = true))
        assertNull(HostChromeColors.pageBase(0xFFF1F2F3.toInt(), dark = true))
        assertNull(HostChromeColors.pageBase(0xFF0A0B0C.toInt(), dark = false))
    }
}
