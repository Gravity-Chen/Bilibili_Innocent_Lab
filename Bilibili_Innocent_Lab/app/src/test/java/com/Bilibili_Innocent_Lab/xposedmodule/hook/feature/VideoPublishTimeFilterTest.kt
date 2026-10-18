package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bilibili.app.comm.list.common.api.model.PlayerArgs
import com.bilibili.pegasus.data.base.BasePegasusData
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class VideoPublishTimeFilterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-18 20:00 北京时间。 */
    private val now: Instant = at(2026, 10, 18, 20, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    private fun exactAt(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): PublishTimeEstimate {
        val instant = at(year, month, day, hour, minute)
        return PublishTimeEstimate(instant, instant.plusSeconds(1))
    }

    private val stale = VideoPublishTextParser.MAX_TEXT_STALENESS_SECONDS

    private fun date(year: Int, month: Int, day: Int) =
        PublishTimePoint.Absolute(LocalDate.of(year, month, day))

    private fun VideoPublishTimeRule.removes(estimate: PublishTimeEstimate?) =
        shouldRemove(estimate, now, zone)

    @Test
    fun `before a date keeps strictly earlier videos only`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4))

        assertFalse(rule.removes(exactAt(2021, 6, 3, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertTrue(rule.removes(exactAt(2024, 1, 1)))
    }

    @Test
    fun `after a date keeps the node day and everything later`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.AFTER, date(2021, 6, 4))

        assertTrue(rule.removes(exactAt(2021, 6, 3, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertFalse(rule.removes(exactAt(2026, 10, 18)))
    }

    @Test
    fun `excluding a range removes both inclusive end days and nothing outside`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.OUTSIDE,
            date(2021, 7, 8),
            date(2021, 9, 1)
        )

        assertFalse(rule.removes(exactAt(2021, 7, 7, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 7, 8, 0, 0)))
        assertTrue(rule.removes(exactAt(2021, 9, 1, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 9, 2, 0, 0)))
    }

    @Test
    fun `keeping only a range removes both sides`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.BETWEEN,
            date(2021, 7, 8),
            date(2021, 9, 1)
        )

        assertTrue(rule.removes(exactAt(2021, 7, 7, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 7, 8)))
        assertFalse(rule.removes(exactAt(2021, 9, 1, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 9, 2)))
    }

    @Test
    fun `older than three months uses calendar months relative to now`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.BEFORE,
            PublishTimePoint.Relative(3, PublishTimeUnit.MONTH)
        )

        // 3 个月前 = 2026-07-18 20:00。
        assertFalse(rule.removes(exactAt(2026, 7, 18, 19, 59)))
        assertTrue(rule.removes(exactAt(2026, 7, 18, 20, 0)))
        assertTrue(rule.removes(exactAt(2026, 10, 1)))
    }

    @Test
    fun `within the last two days keeps only the trailing 48 hours`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(2, PublishTimeUnit.DAY)
        )

        assertTrue(rule.removes(exactAt(2026, 10, 16, 19, 59)))
        assertFalse(rule.removes(exactAt(2026, 10, 16, 20, 0)))
        assertFalse(rule.removes(exactAt(2026, 10, 18, 19, 0)))
    }

    @Test
    fun `relative points move with the evaluation time`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(2, PublishTimeUnit.DAY)
        )
        val video = exactAt(2026, 10, 17, 12, 0)

        assertFalse(rule.shouldRemove(video, now, zone))
        assertTrue(rule.shouldRemove(video, at(2026, 10, 20, 12, 1), zone))
    }

    @Test
    fun `unknown publish time is always kept`() {
        PublishTimeMode.entries.forEach { mode ->
            val rule = if (mode.pointCount == 2) {
                VideoPublishTimeRule.of(mode, date(2021, 1, 1), date(2021, 2, 1))
            } else {
                VideoPublishTimeRule.of(mode, date(2021, 1, 1))
            }
            assertFalse(mode.name, rule.removes(null))
        }
    }

    @Test
    fun `imprecise estimates are removed only when entirely outside the kept side`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4))
        val straddling = PublishTimeEstimate(at(2021, 6, 3, 12), at(2021, 6, 4, 12))
        val entirelyAfter = PublishTimeEstimate(at(2021, 6, 4), at(2021, 6, 5))

        assertFalse(rule.removes(straddling))
        assertTrue(rule.removes(entirelyAfter))

        val exclude = VideoPublishTimeRule.of(PublishTimeMode.OUTSIDE, date(2021, 7, 8), date(2021, 9, 1))
        val partlyInside = PublishTimeEstimate(at(2021, 9, 1, 12), at(2021, 9, 2, 12))
        assertFalse(exclude.removes(partlyInside))
    }

    @Test
    fun `encoding round trips every mode and point kind`() {
        listOf(
            VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4)),
            VideoPublishTimeRule.of(PublishTimeMode.AFTER, PublishTimePoint.Relative(2, PublishTimeUnit.DAY)),
            VideoPublishTimeRule.of(PublishTimeMode.BETWEEN, date(2021, 7, 8), date(2021, 9, 1)),
            VideoPublishTimeRule.of(
                PublishTimeMode.OUTSIDE,
                PublishTimePoint.Relative(7, PublishTimeUnit.DAY),
                PublishTimePoint.Relative(3, PublishTimeUnit.DAY)
            ),
            VideoPublishTimeRule.of(
                PublishTimeMode.BETWEEN,
                date(2026, 1, 1),
                PublishTimePoint.Relative(12, PublishTimeUnit.HOUR)
            )
        ).forEach { rule ->
            val parsed = VideoPublishTimeRule.parse(rule.encode())
            assertTrue(parsed.isEnabled)
            assertEquals(rule.encode(), parsed.encode())
            assertEquals(rule.mode, parsed.mode)
            assertEquals(rule.first, parsed.first)
            assertEquals(rule.second, parsed.second)
        }
        assertEquals("v1|outside|d:2021-07-08|d:2021-09-01",
            VideoPublishTimeRule.of(PublishTimeMode.OUTSIDE, date(2021, 7, 8), date(2021, 9, 1)).encode())
    }

    @Test
    fun `blank text is disabled and corrupt text fails open`() {
        assertSame(VideoPublishTimeRule.DISABLED, VideoPublishTimeRule.parse(""))
        assertFalse(VideoPublishTimeRule.parse("  ").isConfigured)
        listOf(
            "v2|before|d:2021-06-04",
            "v1|sometime|d:2021-06-04",
            "v1|before",
            "v1|before|d:2021-02-30",
            "v1|before|d:1999-01-01",
            "v1|after|r:0d",
            "v1|after|r:10000d",
            "v1|after|r:3x",
            "v1|between|d:2021-06-04",
            "v1|before|d:2021-06-04|d:2021-06-05",
            "v1|between|d:2021-09-01|d:2021-07-08",
            "v1|outside|r:3d|r:7d"
        ).forEach { raw ->
            val rule = VideoPublishTimeRule.parse(raw)
            assertTrue(raw, rule.isConfigured)
            assertFalse(raw, rule.isValid)
            assertFalse(raw, rule.isEnabled)
            assertFalse(raw, rule.removes(exactAt(2021, 6, 4)))
        }
    }

    @Test
    fun `a mixed range that is inverted at evaluation time fails open`() {
        // 起点 2026-10-01，终点"12 小时前"：10-01 之后两周内有效，但若终点早于起点就整体放行。
        val rule = VideoPublishTimeRule.parse("v1|between|d:2026-10-01|r:12h")
        assertTrue(rule.isEnabled)
        assertNotNull(rule.resolveBounds(now, zone))
        val earlyNow = at(2026, 10, 1, 6)
        assertNull(rule.resolveBounds(earlyNow, zone))
        assertFalse(rule.shouldRemove(exactAt(2020, 1, 1), earlyNow, zone))
    }

    @Test
    fun `day range with identical start and end covers that whole day`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BETWEEN, date(2021, 6, 4), date(2021, 6, 4))

        assertFalse(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertFalse(rule.removes(exactAt(2021, 6, 4, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 6, 5, 0, 0)))
    }

    @Test
    fun `parser reads absolute dates as whole server days`() {
        val full = VideoPublishTextParser.parse("某UP主 · 2021-06-04", now)!!
        assertEquals(at(2021, 6, 4), full.earliest)
        assertEquals(at(2021, 6, 5), full.latestExclusive)

        assertEquals(full, VideoPublishTextParser.parse("2021年6月4日", now))
        assertEquals(full, VideoPublishTextParser.parse("2021/06/04 发布", now))
    }

    @Test
    fun `short dates belong to this year unless they would be in the future`() {
        val thisYear = VideoPublishTextParser.parse("UP · 06-04", now)!!
        assertEquals(at(2026, 6, 4), thisYear.earliest)

        val lastYear = VideoPublishTextParser.parse("12-25", now)!!
        assertEquals(at(2025, 12, 25), lastYear.earliest)
        assertEquals(at(2025, 12, 26), lastYear.latestExclusive)
    }

    @Test
    fun `calendar words map to whole days widened for stale text`() {
        val yesterday = VideoPublishTextParser.parse("昨天", now)!!
        assertEquals(at(2026, 10, 17).minusSeconds(stale), yesterday.earliest)
        assertEquals(at(2026, 10, 18), yesterday.latestExclusive)
        assertEquals(at(2026, 10, 17), VideoPublishTextParser.parse("前天 12:30", now)!!.latestExclusive)
        assertEquals(at(2026, 10, 17), VideoPublishTextParser.parse("前天12:30", now)!!.latestExclusive)
        assertEquals(at(2026, 10, 19), VideoPublishTextParser.parse("今天", now)!!.latestExclusive)
    }

    @Test
    fun `relative text widens by one unit on each side`() {
        val estimate = VideoPublishTextParser.parse("UP名 · 3天前", now)!!
        assertEquals(at(2026, 10, 14, 20, 0).minusSeconds(stale), estimate.earliest)
        assertEquals(at(2026, 10, 16, 20, 0).plusSeconds(1), estimate.latestExclusive)

        val hours = VideoPublishTextParser.parse("5小时前", now)!!
        assertTrue(hours.earliest <= at(2026, 10, 18, 14, 0))
        assertTrue(hours.latestExclusive > at(2026, 10, 18, 16, 0))

        assertNotNull(VideoPublishTextParser.parse("刚刚", now))
        assertNotNull(VideoPublishTextParser.parse("2个月前", now))
        assertNotNull(VideoPublishTextParser.parse("1年前", now))
    }

    @Test
    fun `parser ignores non time segments and conflicting segments`() {
        assertNull(VideoPublishTextParser.parse(null, now))
        assertNull(VideoPublishTextParser.parse("", now))
        assertNull(VideoPublishTextParser.parse("影视飓风", now))
        assertNull(VideoPublishTextParser.parse("1.2万播放", now))
        assertNull(VideoPublishTextParser.parse("12:34", now))
        // UP 名里带日期样式但不是整段：不采用。
        assertNull(VideoPublishTextParser.parse("小明06-04号", now))
        assertNull(VideoPublishTextParser.parse("13-40", now))
        assertNull(VideoPublishTextParser.parse("0天前", now))
        assertNull(VideoPublishTextParser.parse("2021-06-04 · 2022-01-01", now))
    }

    @Test
    fun `multiple sources merge by intersection and contradictions become unknown`() {
        val merged = VideoPublishTextParser.parseAll(listOf("3天前", "10-15"), now)!!
        assertEquals(at(2026, 10, 15), merged.earliest)
        assertEquals(at(2026, 10, 16), merged.latestExclusive)

        assertNull(VideoPublishTextParser.parseAll(listOf("2021-06-04", "昨天"), now))
        assertNull(VideoPublishTextParser.parseAll(listOf("UP名", null), now))
    }

    @Test
    fun `parsed relative text never flips a decision near the boundary`() {
        // "3天前"可能是 2–4 天前；"最近两天"规则不应删掉它，"最近五天"必然保留，"最近一天"才删。
        val estimate = VideoPublishTextParser.parse("3天前", now)
        fun lastDays(days: Int) = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(days, PublishTimeUnit.DAY)
        )

        assertFalse(lastDays(5).removes(estimate))
        assertFalse(lastDays(2).removes(estimate))
        assertTrue(lastDays(1).removes(estimate))
    }

    private class HomeCard(private val text: String, private val args: PlayerArgs) : BasePegasusData() {
        override fun getDesc(): String = text
        override fun getPlayerArgs(): PlayerArgs = args
    }

    @Test
    fun `home reader parses the desc of regular videos only`() {
        val gate = BasePegasusData::class.java.getMethod("getPlayerArgs")
        val video = HomeCard("某UP主 · 06-04", PlayerArgs().apply { aid = 42L })
        val live = HomeCard("某UP主 · 06-04", PlayerArgs().apply { aid = 42L; isLive = 1 })
        val noAid = HomeCard("某UP主 · 06-04", PlayerArgs())

        assertEquals(at(2026, 6, 4), VideoPublishTimeReader.fromHomeDesc(video, gate, now)!!.earliest)
        assertNull(VideoPublishTimeReader.fromHomeDesc(live, gate, now))
        assertNull(VideoPublishTimeReader.fromHomeDesc(noAid, gate, now))
        assertNull(VideoPublishTimeReader.fromHomeDesc(HomeCard("某UP主", PlayerArgs().apply { aid = 1L }), gate, now))
    }

    @Test
    fun `stale relative text never removes a card under an older than rule`() {
        // 缓存回放三天后的"3天前"实际可能是 6 天前；"只保留 5 天前之前"的规则不能删它。
        val olderThanFiveDays = VideoPublishTimeRule.of(
            PublishTimeMode.BEFORE,
            PublishTimePoint.Relative(5, PublishTimeUnit.DAY)
        )
        assertFalse(olderThanFiveDays.removes(VideoPublishTextParser.parse("3天前", now)))
        // 绝对日期不受过时影响，照常判定。
        assertTrue(olderThanFiveDays.removes(VideoPublishTextParser.parse("10-17", now)))
    }

    @Test
    fun `absolute points outside the supported years are rejected`() {
        assertNull(PublishTimePoint.decode("d:2101-01-01"))
        assertTrue(runCatching { date(2101, 1, 1) }.isFailure)
        assertTrue(runCatching { date(2008, 12, 31) }.isFailure)
    }

    @Test
    fun `parse probe reports exactly once after the sample fills`() {
        val probe = PublishTimeParseProbe(sampleSize = 3)
        assertNull(probe.record(false))
        assertNull(probe.record(true))
        assertEquals(PublishTimeParseProbe.Sample(parsed = 1, seen = 3), probe.record(false))
        assertNull(probe.record(true))
    }

    open class BaseCoverCard {
        open fun getCoverLeftText1(): String = "1"
    }

    class OverridingCoverCard : BaseCoverCard() {
        override fun getCoverLeftText1(): String = "1.2万"
    }

    @Test
    fun `cover reader resolves getters that a card subclass overrides`() {
        // 基类与子类两条同签名 getter 不能让读取放弃；invoke 分派到子类覆写。
        assertEquals(12_000L, VideoPlayCountReader.coverPlayCount(OverridingCoverCard()))
    }
}
