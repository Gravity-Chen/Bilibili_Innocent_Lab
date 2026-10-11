package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.junit.Assert.*
import org.junit.Test

/** 8.90.2 实际布局：五项 Long，加有 presence 的 Vt/Danmaku StatInfo.value:Long。 */
class HostAgentVideoStatisticsTest {
    private fun reader() = checkNotNull(HostAgentVideoStatistics.resolve(Arc::class.java))

    @Test fun viewUniteLongCountsAndNestedValuesPreserveNumbersAboveIntRange() {
        val large = Int.MAX_VALUE.toLong() + 100
        val stat = Stat(vt = StatInfo(large), danmaku = StatInfo(large + 1), count = large + 2)
        val data = reader().read(Arc(true, stat))
        assertEquals("reported", data.getString("status"))
        assertEquals(large, data.getLong("view_count"))
        assertEquals(large + 1, data.getLong("danmaku_count"))
        for (key in listOf("like_count", "coin_count", "favorite_count", "share_count", "comment_count")) assertEquals(large + 2, data.getLong(key))
        assertEquals(8, data.length())
        assertFalse(data.has("follow"))
        assertEquals(0, stat.getVt()?.textReads)
        assertEquals(0, stat.getDanmaku()?.textReads)
    }

    @Test fun outerPresenceAndDefaultStatKeepAllFieldsUnknown() {
        for (arc in listOf(Arc(false, Stat()), Arc(true, Stat.getDefaultInstance()), Arc(true, null))) {
            val data = reader().read(arc)
            assertEquals("unknown", data.getString("status"))
            assertTrue(data.isNull("view_count"))
            assertTrue(data.isNull("like_count"))
        }
    }

    @Test fun absentNestedPresenceDoesNotEvenReadItsGetter() {
        val stat = Stat(vtPresent = false, danmakuPresent = false)
        val data = reader().read(Arc(true, stat))
        assertEquals(0, stat.vtReads)
        assertEquals(0, stat.danmakuReads)
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("like_count"))
    }

    @Test fun nestedDefaultInstancesAreNotReportedAsNumericZero() {
        val stat = Stat(vt = StatInfo.getDefaultInstance(), danmaku = StatInfo.getDefaultInstance())
        val data = reader().read(Arc(true, stat))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("like_count"))
    }

    @Test fun explicitlyPresentNonDefaultZeroRemainsValidEvenWithStructuralEquality() {
        val info = StatInfo(0)
        assertEquals(StatInfo.getDefaultInstance(), info)
        assertNotSame(StatInfo.getDefaultInstance(), info)
        val stat = Stat(vt = info, danmaku = StatInfo(0), count = 0)
        assertEquals(Stat.getDefaultInstance(), stat)
        assertNotSame(Stat.getDefaultInstance(), stat)
        val data = reader().read(Arc(true, stat))
        assertEquals("reported", data.getString("status"))
        assertEquals(0, data.getLong("view_count"))
        assertEquals(0, data.getLong("danmaku_count"))
        assertEquals(0, data.getLong("like_count"))
    }

    @Test fun negativeNumericValuesDoNotFallBackToFormattedText() {
        val vt = StatInfo(-1)
        val danmaku = StatInfo(-1)
        val data = reader().read(Arc(true, Stat(vt = vt, danmaku = danmaku, count = -1)))
        assertEquals("unknown", data.getString("status"))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertTrue(data.isNull("coin_count"))
        assertEquals(0, vt.textReads)
        assertEquals(0, danmaku.textReads)
    }

    @Test fun nullNestedPayloadLeavesItsFieldUnknownWithoutDiscardingOtherCounts() {
        val data = reader().read(Arc(true, Stat(vt = null, danmaku = null)))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("share_count"))
    }

    @Test fun missingNestedPresenceDisablesTheViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(MissingPresenceArc::class.java))
    }

    @Test fun stringValueCannotEnableNumericViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(StringValueArc::class.java))
    }

    @Test fun missingNestedDefaultInstanceDisablesTheViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(MissingDefaultArc::class.java))
    }

    @Test fun boxedLongValueCannotReplaceTheVerifiedPrimitiveLongField() {
        assertNull(HostAgentVideoStatistics.resolve(BoxedValueArc::class.java))
    }

    class Arc(private val present: Boolean, private val stat: Stat?) {
        fun hasStat() = present
        fun getStat() = stat
    }
    class Stat(private val vtPresent: Boolean = true, private val danmakuPresent: Boolean = true,
               private val vt: StatInfo? = StatInfo(1), private val danmaku: StatInfo? = StatInfo(1), private val count: Long = 1) {
        var vtReads = 0
        var danmakuReads = 0
        fun hasVt() = vtPresent
        fun hasDanmaku() = danmakuPresent
        fun getVt(): StatInfo? { vtReads++; return vt }
        fun getDanmaku(): StatInfo? { danmakuReads++; return danmaku }
        fun getLike() = count
        fun getCoin() = count
        fun getFav() = count
        fun getShare() = count
        fun getReply() = count
        fun getFollow() = 999L
        override fun equals(other: Any?) = other is Stat && count == other.count && vt == other.vt && danmaku == other.danmaku
        override fun hashCode() = count.hashCode()
        companion object {
            private val DEFAULT = Stat(vt = StatInfo.getDefaultInstance(), danmaku = StatInfo.getDefaultInstance(), count = 0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }
    class StatInfo(private val value: Long) {
        var textReads = 0
        fun getValue() = value
        fun getText(): String { textReads++; return "999亿" }
        fun getPureText(): String { textReads++; return "99900000000" }
        override fun equals(other: Any?) = other is StatInfo && value == other.value
        override fun hashCode() = value.hashCode()
        companion object {
            private val DEFAULT = StatInfo(0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }

    class MissingPresenceArc { fun hasStat() = true; fun getStat() = MissingPresenceStat() }
    class MissingPresenceStat {
        fun getVt() = StatInfo(1)
        companion object { @JvmStatic fun getDefaultInstance() = MissingPresenceStat() }
    }
    class StringValueArc { fun hasStat() = true; fun getStat() = StringValueStat() }
    class StringValueStat {
        fun hasVt() = true
        fun getVt() = StringValueInfo()
        companion object { @JvmStatic fun getDefaultInstance() = StringValueStat() }
    }
    class StringValueInfo {
        fun getValue() = "100000000"
        companion object { @JvmStatic fun getDefaultInstance() = StringValueInfo() }
    }
    class MissingDefaultArc { fun hasStat() = true; fun getStat() = MissingDefaultStat() }
    class MissingDefaultStat {
        fun hasVt() = true
        fun getVt() = MissingDefaultInfo()
        companion object { @JvmStatic fun getDefaultInstance() = MissingDefaultStat() }
    }
    class MissingDefaultInfo { fun getValue() = 1L }
    class BoxedValueArc { fun hasStat() = true; fun getStat() = BoxedValueStat() }
    class BoxedValueStat {
        fun hasVt() = true
        fun getVt() = BoxedValueInfo()
        companion object { @JvmStatic fun getDefaultInstance() = BoxedValueStat() }
    }
    class BoxedValueInfo {
        fun getValue(): Long? = 1L
        companion object { @JvmStatic fun getDefaultInstance() = BoxedValueInfo() }
    }
}
