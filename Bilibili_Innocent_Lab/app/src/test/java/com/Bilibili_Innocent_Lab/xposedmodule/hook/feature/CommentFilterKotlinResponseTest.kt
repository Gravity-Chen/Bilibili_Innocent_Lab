package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kpurifyfixture.Content
import kpurifyfixture.FoldListResp
import kpurifyfixture.HotspotPageResp
import kpurifyfixture.ReplyInfo
import kpurifyfixture.ReplyInfoReply
import kpurifyfixture.SubjectControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterKotlinResponseTest {
    private fun reply(text: String, vararg children: ReplyInfo) = ReplyInfo(Content(text, emptyMap()), *children)

    private fun rewriter(rpc: String) = CommentFilterFeatureInstaller.kotlinRewriter(ReplyInfo::class.java, rpc) { replies ->
        replies.filter { reply ->
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals((reply as ReplyInfo).content.message), setOf("blocked"), null
            )
        }.toSet()
    }

    @Test fun `fold list removes blocked replies while retaining control pagination and originals`() {
        val control = SubjectControl(true)
        val pagination = Any()
        val kept = reply("keep", reply("blocked child"), reply("child keep"))
        val source = FoldListResp(listOf(reply("blocked"), kept), control, pagination)

        val result = rewriter("foldList").rewrite(source)
        val updated = result.message as FoldListResp

        assertEquals(2, result.removed)
        assertEquals(listOf("keep"), updated.repliesList.map { it.content.message })
        assertEquals(listOf("child keep"), updated.repliesList.single().repliesList.map { it.content.message })
        assertSame(control, updated.subjectControl)
        assertSame(pagination, updated.paginationReply)
        assertEquals(2, source.repliesList.size)
        assertEquals(2, kept.repliesList.size)
        assertSame(updated, rewriter("foldList").rewrite(updated).message)
    }

    @Test fun `hotspot list keeps opinions and page and returns untouched responses by identity`() {
        val opinion = Any()
        val page = Any()
        val kept = reply("keep")
        val source = HotspotPageResp(listOf(reply("blocked"), kept), listOf(opinion), page)

        val updated = rewriter("hotspotPage").rewrite(source).message as HotspotPageResp

        assertEquals(listOf(kept), updated.repliesList)
        assertSame(opinion, updated.opinionsList.single())
        assertSame(page, updated.page)
        assertEquals(2, source.repliesList.size)
        assertSame(updated, rewriter("hotspotPage").rewrite(updated).message)
    }

    @Test fun `broadcast reply can be removed only in its rpc and never changes the default singleton`() {
        val source = ReplyInfoReply(reply("blocked"))
        val default = ReplyInfoReply.getDefaultInstance()

        assertSame(source, rewriter("detailList").rewrite(source).message)
        val result = rewriter("replyInfo").rewrite(source)
        assertEquals(1, result.removed)
        assertFalse((result.message as ReplyInfoReply).hasReply())
        assertTrue(source.hasReply())
        assertSame(default, rewriter("replyInfo").rewrite(default).message)
        assertFalse(default.hasReply())

        val root = kmossfixture.ReplyInfo.of("blocked", kmossfixture.ReplyInfo.of("blocked"))
        val detail = kmossfixture.MainListReply(emptyList(), null, root)
        val normal = CommentFilterFeatureInstaller.kotlinRewriter(kmossfixture.ReplyInfo::class.java, "detailList") { replies ->
            replies.filter { (it as kmossfixture.ReplyInfo).text == "blocked" }.toSet()
        }
        val updatedDetail = normal.rewrite(detail).message as kmossfixture.MainListReply
        assertEquals("blocked", updatedDetail.root.text)
        assertTrue(updatedDetail.root.repliesList.isEmpty())
        assertEquals(1, root.repliesList.size)
    }
}
