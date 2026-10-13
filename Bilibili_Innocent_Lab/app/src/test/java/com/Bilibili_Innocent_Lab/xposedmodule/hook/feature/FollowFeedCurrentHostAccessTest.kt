package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import org.junit.Assert.*
import org.junit.Test

/** 忠实模拟双9.15.0：b返回顶层扁平list，n保持引用本地list，O只有背景含义。 */
class FollowFeedCurrentHostAccessTest {
    class Item(val identity: Long, var j: Item? = null) {
        val n = ArrayList<Module>()
        fun a() = identity
        fun b(): List<Module> = j?.b() ?: n
        fun h() = j != null
    }
    open class Module(val a: Item) {
        init { a.n += this }
        fun M(): Item = a.j ?: a
        fun O() = true
    }
    class Video(item: Item) : Module(item)
    class Holder(var module: Module?)
    class AmbiguousHolder(var first: Module?, var second: Module?)
    class BadItem {
        var j: BadItem? = null
        val n = "not-a-module-list"
        fun a() = 42L
        fun b(): List<Any> = emptyList()
        fun h() = false
    }
    class BadModule(val a: BadItem) {
        fun M() = a
        fun O() = true
    }

    private fun access() = requireNotNull(FollowFeedHostAccess.current(Module::class.java, Item::class.java))

    @Test fun ordinaryRowsUseTheFlattenedOwnerListByIdentity() {
        val item = Item(42)
        val first = Module(item); val last = Module(item)
        val access = access()
        val row = requireNotNull(access.row(Holder(first), 0))
        assertEquals(42L, row.identity)
        assertTrue(row.first); assertFalse(row.last); assertNull(row.reference)
        val end = requireNotNull(access.row(Holder(last), 1))
        assertFalse(end.first); assertTrue(end.last)
    }

    @Test fun quotedRowsUseLocalNWhenBReturnsTheParentsFlattenedList() {
        val parent = Item(42)
        Module(parent)
        val quoted = Item(77, parent)
        val first = Module(quoted); val last = Video(quoted)
        parent.n.addAll(quoted.n)
        Module(parent)
        assertSame(parent.n, quoted.b())
        val access = access()
        for ((module, firstExpected, lastExpected) in listOf(Triple(first, true, false), Triple(last, false, true))) {
            val row = requireNotNull(access.row(Holder(module), 1))
            assertEquals(42L, row.identity); assertFalse(row.first); assertFalse(row.last)
            val reference = requireNotNull(row.reference)
            assertEquals(77L, reference.identity); assertEquals(firstExpected, reference.first)
            assertEquals(lastExpected, reference.last); assertTrue(reference.quoted)
        }
    }

    @Test fun reboundHolderReadsNewOwnershipAndRejectsStaleLocalMembership() {
        val access = access()
        val parent = Item(42); val quote = Item(77, parent)
        val module = Module(quote); parent.n.addAll(quote.n)
        val holder = Holder(module)
        assertEquals(77L, access.row(holder, 1)?.reference?.identity)
        holder.module = Module(Item(88))
        assertEquals(88L, access.row(holder, 0)?.identity)
        assertNull(access.row(holder, 0)?.reference)
        holder.module = module
        quote.n.clear()
        assertNull(access.row(holder, 1))
        assertNull(access.row(AmbiguousHolder(module, module), 1))
    }

    @Test fun invalidIdentitiesAndWrongLocalListAbiFailOpen() {
        assertNull(access().row(Holder(Module(Item(-1))), 0))
        val parent = Item(42); val quote = Item(0, parent)
        val module = Module(quote); parent.n.addAll(quote.n)
        assertNull(access().row(Holder(module), 1))
        assertNull(FollowFeedHostAccess.current(BadModule::class.java, BadItem::class.java))
    }

    @Test fun booleanOIsNeverTreatedAsLocalOwnerAndUnknownVideoTypesDoNotGainFrames() {
        val access = access()
        assertFalse(access.embeddedVideoSupported)
        assertNull(FollowFeedHostAccess.legacy(Module::class.java, Item::class.java, Video::class.java))
        val item = Item(42)
        Module(item); val video = Video(item)
        assertNull(access.row(Holder(video), 1)?.reference)
    }

    @Test fun cyclesAndOversizedModuleListsStopBeforeHostRecursionOrUnboundedTraversal() {
        val access = access()
        val cyclic = Item(42); val module = Module(cyclic); cyclic.j = cyclic
        assertNull(access.row(Holder(module), 0))
        assertEquals("parent-chain-unavailable", access.unavailableReason)
        val oversized = Item(77); val first = Module(oversized)
        repeat(FollowFeedHostAccess.MAX_ROW_MODULES) { Module(oversized) }
        assertNull(access.row(Holder(first), 0))
        assertEquals("local-module-budget", access.unavailableReason)
        val valid = Module(Item(88))
        assertEquals(88L, access.row(Holder(valid), 0)?.identity)
        assertNull(access.unavailableReason)
    }
}
