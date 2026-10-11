package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAgentDiscoveryRpcTest {
    private fun video(id: String) = HostAgentVideo(id, "标题", "作者", 123L, "01:00")

    @Test fun `missing verified host classes disable native datasets and refuse malformed arguments`() {
        val rpc = HostAgentDiscoveryRpc(object : ClassLoader(null) {})
        assertFalse(rpc.rankingAvailable)
        assertFalse(rpc.regionsAvailable)
        assertFalse(rpc.popularAvailable)
        assertEquals("ranking_unavailable", runCatching { rpc.ranking(null, null, 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_cursor_invalid", runCatching { rpc.ranking(null, "2", 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_region_invalid", runCatching { rpc.ranking("-1", null, 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_regions_unavailable", runCatching { rpc.regions(1000) }.exceptionOrNull()?.message)
        assertEquals("popular_unavailable", runCatching { rpc.popular(null, 1000) }.exceptionOrNull()?.message)
        assertEquals("popular_cursor_invalid", runCatching { rpc.popular("unsafe", 1000) }.exceptionOrNull()?.message)
    }

    @Test fun `invalid first card cannot promote second video to rank one`() {
        val page = HostAgentDiscoveryProjection.page("ranking", listOf(null, video("av2"), video("av3")))
        val entries = page.data.getJSONArray("videos")
        assertEquals("av2", entries.getJSONObject(0).getString("video_id"))
        assertEquals(2, entries.getJSONObject(0).getInt("ranking_position"))
        assertEquals(3, entries.getJSONObject(1).getInt("ranking_position"))
    }

    @Test fun `duplicate and malformed cards retain source rank gaps and page offsets`() {
        val page = HostAgentDiscoveryProjection.page("ranking",
            listOf(video("av1"), video("av1"), video("https://evil.example"), video("av4")), page = 2)
        assertEquals(listOf("av1", "av4"), page.videos.map { it.id })
        assertEquals(21, page.data.getJSONArray("videos").getJSONObject(0).getInt("ranking_position"))
        assertEquals(24, page.data.getJSONArray("videos").getJSONObject(1).getInt("ranking_position"))
    }

    @Test fun `popular order is explicitly distinct from ranking and opaque cursor is not exposed in data`() {
        val page = HostAgentDiscoveryProjection.page("popular", listOf(null, video("av2")), next = "backend-only")
        val entry = page.data.getJSONArray("videos").getJSONObject(0)
        assertEquals(2, entry.getInt("source_position"))
        assertEquals("popular_feed_order", entry.getString("position_basis"))
        assertFalse(entry.has("ranking_position"))
        assertFalse(page.data.toString().contains("backend-only"))
        assertEquals("backend-only", page.next)
        assertEquals("unknown", entry.getString("official_source"))
    }

    @Test fun `projection and page cursors stay bounded and reject coercions`() {
        val page = HostAgentDiscoveryProjection.page("ranking", (1..300).map { video("av$it") })
        assertEquals(20, page.videos.size)
        assertEquals(1, HostAgentDiscoveryProjection.rankingPage(null))
        assertEquals(10, HostAgentDiscoveryProjection.rankingPage("rank:10"))
        listOf("rank:1", "rank:11", "rank:02", "2", "rank:-1", "rank:2\n", "rank:9999999999999999").forEach {
            assertNull(it, HostAgentDiscoveryProjection.rankingPage(it))
        }
        assertEquals(123, HostAgentDiscoveryProjection.regionId("123"))
        assertEquals(0, HostAgentDiscoveryProjection.regionId("0"))
        listOf("-1", "01", "2147483648", "123x", "123\n", " 123").forEach {
            assertNull(it, HostAgentDiscoveryProjection.regionId(it))
        }
    }

    class RankItem(private val id: String = "123", private val route: String = "bilibili://video/123",
                   private val kind: String = "av", private val seconds: Long = 3661) {
        fun getGoto() = kind
        fun getParam() = id
        fun getUri() = route
        fun getTitle() = "<em>标题</em>\u0000" + "x".repeat(400)
        fun getName() = "作者"
        fun getMid() = 456L
        fun getDuration() = seconds
    }

    class DriftedRankItem {
        fun getGoto() = "av"
        fun getParam() = "123"
        fun getUri() = "bilibili://video/123"
        fun getTitle() = "标题"
        fun getName() = "作者"
        fun getMid() = "456"
        fun getDuration() = 60L
    }

    @Test fun `rank reader rejects nonvideo and conflicting identity and projects bounded direct facts`() {
        val reader = checkNotNull(HostAgentRankItemReader.resolve(RankItem::class.java))
        assertNull(reader.read(RankItem(kind = "bangumi")))
        assertNull(reader.read(RankItem(route = "bilibili://video/999")))
        assertNull(reader.read(RankItem(id = "0", route = "bilibili://search?keyword=123")))
        val first = checkNotNull(reader.read(RankItem()))
        assertEquals("av123", first.id)
        assertTrue(first.title!!.length in 1..300)
        assertFalse(first.title!!.contains("<em>"))
        assertFalse(first.title!!.contains('\u0000'))
        assertEquals("作者", first.author)
        assertEquals(456L, first.mid)
        assertEquals("1:01:01", first.duration)
        assertEquals("0:00", reader.read(RankItem(seconds = 0))?.duration)
        assertNull(reader.read(RankItem(seconds = -1))?.duration)
        assertEquals("av999", reader.read(RankItem("999", "bilibili://video/999"))?.id)
        assertNull(HostAgentRankItemReader.resolve(DriftedRankItem::class.java))
    }

    class Args {
        fun getUpId() = 789L
        fun getUpName() = "热门作者"
    }

    class Base(private val ad: Boolean = false, private val argsPresent: Boolean = true,
               private val id: String = "123", private val kind: String = "av") {
        fun hasAdInfo() = ad
        fun getGoto() = kind
        fun getParam() = id
        fun getIdx() = 987L
        fun getUri() = "bilibili://video/$id"
        fun getTitle() = "热门视频"
        fun hasArgs() = argsPresent
        fun getArgs() = Args()
    }

    class Cover(private val baseValue: Base, private val present: Boolean = true) {
        fun hasBase() = present
        fun getBase() = baseValue
    }

    class Card(private val cover: Cover, private val present: Boolean = true) {
        fun hasSmallCoverV5() = present
        fun getSmallCoverV5() = cover
    }

    class HeaderCard(private val cover: Cover) {
        fun hasPopularTopEntrance() = true
        fun getPopularTopEntrance() = cover
    }

    @Test fun `popular reader excludes ads missing base and nonvideo cards without inventing owner or duration`() {
        val reader = checkNotNull(HostAgentPopularCardReader.resolve(Card::class.java, Base::class.java))
        assertNull(reader.read(Card(Cover(Base(ad = true)))))
        assertNull(reader.read(Card(Cover(Base()), present = false)))
        assertNull(reader.read(Card(Cover(Base(), present = false))))
        assertNull(reader.read(Card(Cover(Base(kind = "article")))))
        val video = checkNotNull(reader.read(Card(Cover(Base()))))
        assertEquals("av123", video.id)
        assertEquals("热门作者", video.author)
        assertEquals(789L, video.mid)
        assertNull(video.duration)
        val unknownOwner = checkNotNull(reader.read(Card(Cover(Base(argsPresent = false)))))
        assertNull(unknownOwner.author)
        assertNull(unknownOwner.mid)
        assertNotNull(reader.read(Card(Cover(Base(id = "999")))))
    }

    @Test fun `popular continuation can follow the actual final nonvideo card without authorizing it as video`() {
        val reader = checkNotNull(HostAgentPopularPositionReader.resolve(HeaderCard::class.java, Base::class.java))
        val header = HeaderCard(Cover(Base(id = "entrance", kind = "popular_top_entrance")))
        assertEquals(987L to "entrance", reader.read(header))
        assertNull(reader.read(HeaderCard(Cover(Base(), present = false))))
        assertNull(reader.read(Any()))
        assertNull(HostAgentPopularCardReader.resolve(HeaderCard::class.java, Base::class.java))
    }

    class Region(private val id: Int, private val type: Int, private val title: String = "分区") {
        fun getTid() = id
        fun getName() = title
        fun getRegionType() = type
    }

    @Test fun `region catalog retains equal ids of distinct categories and bounds returned facts`() {
        val reader = checkNotNull(HostAgentRegionReader.resolve(Region::class.java))
        assertNull(reader.read(Region(-1, 2)))
        assertNull(reader.read(Region(123, -1)))
        assertNull(reader.read(Any()))
        val data = HostAgentDiscoveryProjection.regions(listOf(
            reader.read(Region(0, 0)), reader.read(Region(0, 1)), reader.read(Region(0, 1)),
            reader.read(Region(123, 2, "x".repeat(500)))))
        val regions = data.getJSONArray("regions")
        assertEquals(3, regions.length())
        assertEquals(0, regions.getJSONObject(0).getInt("region_type"))
        assertEquals(1, regions.getJSONObject(1).getInt("region_type"))
        assertEquals(100, regions.getJSONObject(2).getString("name").length)
        assertEquals(100, HostAgentDiscoveryProjection.regions((1..300).map { HostAgentRegion(it, "分区", 2) })
            .getJSONArray("regions").length())
        assertEquals("host_rpc", data.getString("source"))
        assertFalse(data.toString().contains("uri"))
    }

    @Test fun `popular cursors preserve actual continuation and reject unbounded coerced or repeated values`() {
        val initial = checkNotNull(HostAgentPopularCursor.parse(null))
        assertEquals(0L, initial.idx)
        assertEquals("", initial.lastParam)
        assertEquals(1, initial.page)
        val encoded = checkNotNull(initial.next(987L, "BV1xx411c7mD", "server-version"))
        val page = checkNotNull(HostAgentPopularCursor.parse(encoded))
        assertEquals(987L, page.idx)
        assertEquals("BV1xx411c7mD", page.lastParam)
        assertEquals("server-version", page.version)
        assertEquals(2, page.page)
        assertNull(page.next(987L, "same", "server-version"))
        assertNull(initial.next(0, "", ""))
        assertNull(initial.next(1, "x".repeat(1001), ""))
        assertNull(initial.next(1, "x\ny", ""))
        assertNull(page.copy(page = 10).next(999L, "next", "version"))
        listOf(JSONObject(encoded).put("idx", 987), JSONObject(encoded).put("idx", "9223372036854775808"),
            JSONObject(encoded).put("page", 2.5), JSONObject(encoded).put("page", 11),
            JSONObject(encoded).put("extra", "hidden"), JSONObject(encoded).put("last_param", JSONObject.NULL)).forEach {
            assertNull(it.toString(), HostAgentPopularCursor.parse(it.toString()))
        }
    }

    @Test fun `popular projection stops continuation when bounding would omit unreturned videos`() {
        val cursor = checkNotNull(HostAgentPopularCursor.parse(null))
        val partial = HostAgentDiscoveryProjection.popular((1..21).map { video("av$it") }, 21,
            cursor, 987L to "last-raw-card", "version")
        assertEquals(20, partial.videos.size)
        assertEquals(true, partial.data.getBoolean("truncated"))
        assertNull(partial.next)
        val full = HostAgentDiscoveryProjection.popular(listOf(video("av1"), null), 2,
            cursor, 987L to "last-raw-card", "version")
        assertFalse(full.data.getBoolean("truncated"))
        val next = checkNotNull(HostAgentPopularCursor.parse(full.next))
        assertEquals(987L, next.idx)
        assertEquals("last-raw-card", next.lastParam)
        assertFalse(full.data.toString().contains("last-raw-card"))
        assertNull(HostAgentDiscoveryProjection.popular(listOf(video("av1")), 1, cursor, null, "version").next)
    }

    @Test fun `ranking refuses guessed page offsets when host returns a different page size`() {
        val videos = (1..20).map { video("av$it") }
        val full = HostAgentDiscoveryProjection.ranking(videos, 20, 1, null)
        assertEquals("rank:2", full.next)
        assertFalse(full.data.getBoolean("truncated"))
        val over = HostAgentDiscoveryProjection.ranking(videos, 100, 1, null)
        assertEquals(true, over.data.getBoolean("truncated"))
        assertNull(over.next)
        assertEquals(1, over.data.getJSONArray("videos").getJSONObject(0).getInt("ranking_position"))
        assertEquals("ranking_page_size_mismatch", runCatching {
            HostAgentDiscoveryProjection.ranking(videos, 100, 2, null)
        }.exceptionOrNull()?.message)
        assertNull(HostAgentDiscoveryProjection.ranking(videos, 20, 10, null).next)
    }
}
