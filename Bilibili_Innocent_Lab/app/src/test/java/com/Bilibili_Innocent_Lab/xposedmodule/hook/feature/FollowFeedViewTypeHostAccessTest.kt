package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.bilibili.bplus.followinglist.module.item.ModuleEnum
import org.junit.Assert.*
import org.junit.Test

class FollowFeedViewTypeHostAccessTest {
    class Module(item: tS.H, viewType: Int) : tS.G(item, viewType)
    class Holder(var module: tS.G?)
    private fun access() = requireNotNull(FollowFeedHostAccess.current(tS.G::class.java, tS.H::class.java))

    @Test fun eachOfficialDescriptionKindBeforeArchiveCreatesAFrameWithoutGuessingClassNamesOrIds() {
        for (kind in listOf(ModuleEnum.Desc, ModuleEnum.OpusDesc, ModuleEnum.OpusDescList)) {
            val item = tS.H()
            Module(item, kind.viewType())
            val archive = Module(item, ModuleEnum.Archive.viewType())
            val access = access()
            assertTrue(access.embeddedVideoSupported)
            val reference = requireNotNull(access.row(Holder(archive), 1)?.reference)
            assertEquals(42L, reference.identity)
            assertTrue(reference.first && reference.last); assertFalse(reference.quoted)
        }
    }

    @Test fun laterDescriptionsUnrelatedKindsAndUnknownDescriptorsNeverGainVideoFrames() {
        val item = tS.H()
        Module(item, ModuleEnum.Author.viewType())
        val archive = Module(item, ModuleEnum.Archive.viewType())
        Module(item, ModuleEnum.Desc.viewType())
        assertNull(access().row(Holder(archive), 1)?.reference)
        val unknownItem = tS.H(); val unknown = Module(unknownItem, Int.MIN_VALUE)
        val access = access()
        assertNull(access.row(Holder(unknown), 0))
        assertEquals("module-descriptor-unavailable", access.unavailableReason)
    }

    @Test fun conflictingOfficialViewTypesKeepCoreGroupingButDisableTheOptionalVideoFrame() {
        val field = ModuleEnum::class.java.getDeclaredField("type").apply { isAccessible = true }
        val original = field.getInt(ModuleEnum.Footer)
        try {
            field.setInt(ModuleEnum.Footer, ModuleEnum.Archive.viewType())
            val access = access()
            assertFalse(access.embeddedVideoSupported)
            val item = tS.H()
            Module(item, ModuleEnum.Desc.viewType())
            val archive = Module(item, ModuleEnum.Archive.viewType())
            val row = requireNotNull(access.row(Holder(archive), 1))
            assertEquals(42L, row.identity); assertNull(row.reference)
        } finally {
            field.setInt(ModuleEnum.Footer, original)
        }
    }
}
