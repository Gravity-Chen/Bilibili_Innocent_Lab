package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 相对时间点的单位；[code] 是持久化编码，不能改。 */
internal enum class PublishTimeUnit(val code: Char, val chronoUnit: ChronoUnit) {
    HOUR('h', ChronoUnit.HOURS),
    DAY('d', ChronoUnit.DAYS),
    WEEK('w', ChronoUnit.WEEKS),
    MONTH('m', ChronoUnit.MONTHS),
    YEAR('y', ChronoUnit.YEARS);

    companion object {
        fun fromCode(code: Char): PublishTimeUnit? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 发布时间过滤里的一个"时间节点"。
 *
 * - [Absolute]：某个日期的 00:00（用户设备时区）。
 * - [Relative]：评估时刻往前推 N 个单位；月/年按日历减，不是固定秒数。
 *   每次判断都用当前时刻重新解析，"最近两天"不会因为宿主进程长时间不重启而过期。
 */
internal sealed interface PublishTimePoint {
    fun resolve(now: Instant, zone: ZoneId): Instant
    fun encode(): String

    data class Absolute(val date: LocalDate) : PublishTimePoint {
        init {
            require(date.year in MIN_YEAR..MAX_YEAR) { "year out of range: ${date.year}" }
        }

        override fun resolve(now: Instant, zone: ZoneId): Instant =
            date.atStartOfDay(zone).toInstant()

        override fun encode(): String = "d:$date"
    }

    data class Relative(val amount: Int, val unit: PublishTimeUnit) : PublishTimePoint {
        init {
            require(amount in 1..MAX_RELATIVE_AMOUNT) { "amount out of range: $amount" }
        }

        override fun resolve(now: Instant, zone: ZoneId): Instant =
            ZonedDateTime.ofInstant(now, zone).minus(amount.toLong(), unit.chronoUnit).toInstant()

        override fun encode(): String = "r:$amount${unit.code}"
    }

    companion object {
        const val MAX_RELATIVE_AMOUNT = 9_999

        fun decode(raw: String): PublishTimePoint? = when {
            raw.startsWith("d:") -> runCatching {
                Absolute(LocalDate.parse(raw.substring(2)))
            }.getOrNull()
            raw.startsWith("r:") && raw.length >= 4 -> {
                val unit = PublishTimeUnit.fromCode(raw.last())
                val amount = raw.substring(2, raw.length - 1).toIntOrNull()
                if (unit == null || amount == null || amount !in 1..MAX_RELATIVE_AMOUNT) {
                    null
                } else {
                    Relative(amount, unit)
                }
            }
            else -> null
        }

        /** B 站 2009 年上线；更早或太远的日期只可能是输入错误。 */
        const val MIN_YEAR = 2009
        const val MAX_YEAR = 2100
    }
}

/** 规则保留哪一侧；[code] 是持久化编码。 */
internal enum class PublishTimeMode(val code: String, val pointCount: Int) {
    /** 只保留早于节点发布的视频。 */
    BEFORE("before", 1),

    /** 只保留在节点及之后发布的视频。 */
    AFTER("after", 1),

    /** 只保留两个节点之间发布的视频。 */
    BETWEEN("between", 2),

    /** 保留两个节点之间以外的视频（排除这段时间）。 */
    OUTSIDE("outside", 2);

    companion object {
        fun fromCode(code: String): PublishTimeMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 一张卡片的发布时间：可能是精确时间戳，也可能是"3天前"这类文案推出来的区间。
 *
 * 统一成半开区间 `[earliest, latestExclusive)`：真实发布时刻一定落在区间内。
 */
internal data class PublishTimeEstimate(
    val earliest: Instant,
    val latestExclusive: Instant
) {
    init {
        require(earliest < latestExclusive) { "empty publish time estimate" }
    }

    fun intersect(other: PublishTimeEstimate): PublishTimeEstimate? {
        val start = maxOf(earliest, other.earliest)
        val end = minOf(latestExclusive, other.latestExclusive)
        return if (start < end) PublishTimeEstimate(start, end) else null
    }
}

/** 一次列表过滤共用的评估时刻与规则时区；每张卡各取一次 now/zone 既浪费也会跨秒抖动。 */
internal data class PublishTimeContext(val now: Instant, val zone: ZoneId)

/**
 * 推荐视频发布时间过滤规则。
 *
 * 持久化为一行文本：`v1|<mode>|<point>[|<point>]`，例如
 * `v1|before|d:2021-06-04`、`v1|after|r:2d`、`v1|outside|d:2021-07-08|d:2021-09-01`。
 * 空串表示未配置；无法解析的文本整体失效（保守放行），不会误删推荐。
 *
 * 区间规则里绝对日期的**终点含当天**：`2021-07-08 ~ 2021-09-01` 覆盖到 09-01 23:59:59。
 * 单节点规则的绝对日期就是当天 00:00 这个时间点，"早于"不含当天、"晚于"含当天。
 *
 * 判定是区间安全的：只有当卡片发布时间区间**整体**落在保留范围之外才删除，
 * 文案精度不足以下结论时一律保留；读不到发布时间也一律保留。
 */
internal class VideoPublishTimeRule private constructor(
    val mode: PublishTimeMode?,
    val first: PublishTimePoint?,
    val second: PublishTimePoint?,
    val isConfigured: Boolean
) {
    val isValid: Boolean
        get() = !isConfigured || mode != null

    val isEnabled: Boolean
        get() = isConfigured && mode != null

    fun encode(): String = if (mode == null || first == null) {
        ""
    } else {
        buildString {
            append(VERSION).append('|').append(mode.code).append('|').append(first.encode())
            second?.let { append('|').append(it.encode()) }
        }
    }

    /**
     * 当前时刻下保留范围的半开区间端点；单节点规则另一端为 null。
     * 区间规则两端解析后为空或倒置时返回 null（规则此刻失效、保守放行）。
     */
    fun resolveBounds(now: Instant, zone: ZoneId): ResolvedBounds? {
        val currentMode = mode ?: return null
        val start = first ?: return null
        return when (currentMode) {
            PublishTimeMode.BEFORE, PublishTimeMode.AFTER ->
                ResolvedBounds(start.resolve(now, zone), null)
            PublishTimeMode.BETWEEN, PublishTimeMode.OUTSIDE -> {
                val end = second ?: return null
                val startInstant = start.resolve(now, zone)
                val endInstant = when (end) {
                    is PublishTimePoint.Absolute ->
                        end.date.plusDays(1).atStartOfDay(zone).toInstant()
                    is PublishTimePoint.Relative -> end.resolve(now, zone)
                }
                if (startInstant < endInstant) ResolvedBounds(startInstant, endInstant) else null
            }
        }
    }

    /** 未知发布时间、规则失效或区间倒置时一律返回 false。 */
    fun shouldRemove(estimate: PublishTimeEstimate?, now: Instant, zone: ZoneId): Boolean {
        if (!isEnabled || estimate == null) return false
        val bounds = resolveBounds(now, zone) ?: return false
        val earliest = estimate.earliest
        val latest = estimate.latestExclusive
        return when (mode!!) {
            PublishTimeMode.BEFORE -> earliest >= bounds.start
            PublishTimeMode.AFTER -> latest <= bounds.start
            PublishTimeMode.BETWEEN -> latest <= bounds.start || earliest >= bounds.end!!
            PublishTimeMode.OUTSIDE -> earliest >= bounds.start && latest <= bounds.end!!
        }
    }

    data class ResolvedBounds(val start: Instant, val end: Instant?)

    companion object {
        const val VERSION = "v1"

        val DISABLED = VideoPublishTimeRule(null, null, null, isConfigured = false)

        fun of(
            mode: PublishTimeMode,
            first: PublishTimePoint,
            second: PublishTimePoint? = null
        ): VideoPublishTimeRule {
            require((mode.pointCount == 2) == (second != null)) { "point count mismatch for $mode" }
            if (mode.pointCount == 2) {
                require(isStaticallyOrdered(first, second!!)) { "range end precedes start" }
            }
            return VideoPublishTimeRule(mode, first, second, isConfigured = true)
        }

        fun parse(raw: String?): VideoPublishTimeRule {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return DISABLED
            val invalid = VideoPublishTimeRule(null, null, null, isConfigured = true)
            val parts = text.split('|')
            if (parts.size !in 3..4 || parts[0] != VERSION) return invalid
            val mode = PublishTimeMode.fromCode(parts[1]) ?: return invalid
            if (parts.size - 2 != mode.pointCount) return invalid
            val first = PublishTimePoint.decode(parts[2]) ?: return invalid
            val second = if (mode.pointCount == 2) {
                PublishTimePoint.decode(parts[3]) ?: return invalid
            } else {
                null
            }
            if (second != null && !isStaticallyOrdered(first, second)) return invalid
            return VideoPublishTimeRule(mode, first, second, isConfigured = true)
        }

        /**
         * 只拒绝"任何时刻都必然倒置"的区间：两个绝对日期起点晚于终点，
         * 或两个同单位相对点起点更近。混合类型的先后随时间变化，留给运行期判断。
         */
        private fun isStaticallyOrdered(first: PublishTimePoint, second: PublishTimePoint): Boolean =
            when {
                first is PublishTimePoint.Absolute && second is PublishTimePoint.Absolute ->
                    !first.date.isAfter(second.date)
                first is PublishTimePoint.Relative && second is PublishTimePoint.Relative &&
                    first.unit == second.unit -> first.amount > second.amount
                else -> true
            }
    }
}

/**
 * 卡片展示文案里的发布时间解析。
 *
 * 2026-10-18 静态核对 35 个宿主版本（8.84.0–9.15.0）：首页 pegasus 与详情页
 * `RelateCard/RelateAVCard/RelatedAICard/CardBasicInfo/view.v1.Relate` 均**没有**结构化
 * 发布时间 getter，客户端也不在这些卡片上格式化"N天前"——能拿到的只有服务端下发的
 * 描述文案（首页 `getDesc`、详情页 `CardBasicInfo#getDesc` / `Relate#getDesc`）。
 *
 * 文案按 `·`/`•`/`|` 切段，**只有整段**恰好是时间格式才采用，UP 名里碰巧带数字不会误判。
 * 相对文案（"3天前"）两侧各放宽一个单位，日期文案按服务端时区（Asia/Shanghai）取整天。
 */
internal object VideoPublishTextParser {

    val SERVER_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private const val MAX_AGO_AMOUNT = 10_000L
    private val SEPARATORS = Regex("[·•|｜]")
    private val TRAILING_VERBS = listOf("发布", "投稿", "更新", "上传")
    private val AGO = Regex("^(\\d{1,5})(分钟|小时|天|周|星期|个月|月|年)前$")
    private val FULL_DATE = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$")
    private val FULL_DATE_CN = Regex("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$")
    private val SHORT_DATE = Regex("^(\\d{1,2})[-/](\\d{1,2})$")
    private val SHORT_DATE_CN = Regex("^(\\d{1,2})月(\\d{1,2})日$")
    private val TIME_SUFFIX = Regex("\\s*\\d{1,2}:\\d{2}$")

    /** 分钟/小时级文案的额外余量：覆盖服务端缓存与渲染延迟。 */
    private const val SHORT_UNIT_SLACK_SECONDS = 10L * 60

    /**
     * 相对文案（"3天前""昨天""刚刚"）是服务端**下发那一刻**算的，过滤却发生在列表被读取时；
     * 首页冷启动会先回放缓存的上一屏，读到的文案可能已经过时。过时只会让卡片显得比实际更新，
     * 所以只把区间**更早的一端**再放宽这么久：真实发布时间更早时不会被"早于/排除"类规则误删。
     * 绝对日期文案不受影响。
     */
    internal const val MAX_TEXT_STALENESS_SECONDS = 3L * 24 * 60 * 60

    fun parse(text: String?, now: Instant, zone: ZoneId = SERVER_ZONE): PublishTimeEstimate? {
        if (text.isNullOrBlank()) return null
        val estimates = text.split(SEPARATORS)
            .mapNotNull { parseSegment(it, now, zone) }
            .distinct()
        return estimates.singleOrNull()
    }

    /** 多个来源（多个 getter）合并：取交集，互相矛盾时视为未知。 */
    fun parseAll(texts: Iterable<String?>, now: Instant, zone: ZoneId = SERVER_ZONE): PublishTimeEstimate? {
        var merged: PublishTimeEstimate? = null
        for (text in texts) {
            val estimate = parse(text, now, zone) ?: continue
            merged = if (merged == null) estimate else merged.intersect(estimate) ?: return null
        }
        return merged
    }

    internal fun parseSegment(raw: String, now: Instant, zone: ZoneId): PublishTimeEstimate? {
        var segment = raw.trim()
        if (segment.isEmpty()) return null
        TRAILING_VERBS.firstOrNull { segment.endsWith(it) }?.let { verb ->
            segment = segment.dropLast(verb.length).trim()
        }
        segment = segment.replace(TIME_SUFFIX, "")
        val today = ZonedDateTime.ofInstant(now, zone).toLocalDate()
        when (segment) {
            "刚刚" -> return PublishTimeEstimate(
                now.minusSeconds(SHORT_UNIT_SLACK_SECONDS * 2 + MAX_TEXT_STALENESS_SECONDS),
                now.plusSeconds(1)
            )
            "今天" -> return wholeDay(today, zone).allowingStaleness()
            "昨天", "昨日" -> return wholeDay(today.minusDays(1), zone).allowingStaleness()
            "前天" -> return wholeDay(today.minusDays(2), zone).allowingStaleness()
        }
        val compact = segment.replace(" ", "")
        AGO.matchEntire(compact)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            if (amount !in 1..MAX_AGO_AMOUNT) return null
            return ago(amount, match.groupValues[2], now, zone)
        }
        (FULL_DATE.matchEntire(compact) ?: FULL_DATE_CN.matchEntire(compact))?.let { match ->
            val date = date(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            ) ?: return null
            return wholeDay(date, zone)
        }
        (SHORT_DATE.matchEntire(compact) ?: SHORT_DATE_CN.matchEntire(compact))?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            // 当年的日期只显示月-日；落在今天之后说明是去年。
            val thisYear = date(today.year, month, day)
            val resolved = when {
                thisYear != null && !thisYear.isAfter(today) -> thisYear
                else -> date(today.year - 1, month, day)
            } ?: return null
            return wholeDay(resolved, zone)
        }
        return null
    }

    private fun ago(amount: Long, unitText: String, now: Instant, zone: ZoneId): PublishTimeEstimate? {
        val unit = when (unitText) {
            "分钟" -> ChronoUnit.MINUTES
            "小时" -> ChronoUnit.HOURS
            "天" -> ChronoUnit.DAYS
            "周", "星期" -> ChronoUnit.WEEKS
            "个月", "月" -> ChronoUnit.MONTHS
            "年" -> ChronoUnit.YEARS
            else -> return null
        }
        val slack = if (unit == ChronoUnit.MINUTES || unit == ChronoUnit.HOURS) {
            SHORT_UNIT_SLACK_SECONDS
        } else {
            0L
        }
        val zoned = ZonedDateTime.ofInstant(now, zone)
        // "N单位前"可能是向下也可能是四舍五入取整，两侧各放宽一个单位。
        val earliest = zoned.minus(amount + 1, unit).toInstant()
            .minusSeconds(slack + MAX_TEXT_STALENESS_SECONDS)
        val latest = zoned.minus(amount - 1, unit).toInstant().plusSeconds(1)
        return PublishTimeEstimate(earliest, latest)
    }

    private fun PublishTimeEstimate.allowingStaleness(): PublishTimeEstimate =
        copy(earliest = earliest.minusSeconds(MAX_TEXT_STALENESS_SECONDS))

    private fun wholeDay(date: LocalDate, zone: ZoneId): PublishTimeEstimate = PublishTimeEstimate(
        date.atStartOfDay(zone).toInstant(),
        date.plusDays(1).atStartOfDay(zone).toInstant()
    )

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        if (year !in PublishTimePoint.MIN_YEAR..PublishTimePoint.MAX_YEAR) {
            null
        } else {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
}

/**
 * 首页卡片的描述文案读取。
 *
 * `BasePegasusData` 在 35 个版本里都**没有**声明 `getDesc`，它在具体卡片子类上，
 * 所以与封面播放量文案一样按运行期 Class 缓存唯一的公开 getter，不进适配 JSON。
 */
internal object VideoPublishTimeReader {

    private val ABSENT = Any()
    private val descGetters = ConcurrentHashMap<Class<*>, Any>()

    fun fromHomeDesc(item: Any, playerArgsGetter: Method, now: Instant): PublishTimeEstimate? {
        if (!VideoPlayCountReader.isHomeAvVod(item, playerArgsGetter)) return null
        return fromDesc(item, now)
    }

    /** 调用方已经确认过是普通投稿时用，避免重复走 PlayerArgs 闸门。 */
    fun fromDesc(item: Any, now: Instant): PublishTimeEstimate? {
        val getter = cachedDescGetter(item.javaClass) ?: return null
        val text = runCatching { getter.invoke(item) }.getOrNull() as? String
        return VideoPublishTextParser.parse(text, now)
    }

    private fun cachedDescGetter(type: Class<*>): Method? {
        val cached = descGetters.computeIfAbsent(type) { owner ->
            runCatching {
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getDesc" && method.parameterCount == 0 &&
                        method.isPublic && !method.isStatic &&
                        method.returnType == classOf<String>()
                }
                    // 子类覆写时基类与子类各出一条；签名相同即同一个虚方法，invoke 照样分派到子类。
                    .firstOrNull()
            }.getOrNull() ?: ABSENT
        }
        return cached as? Method
    }
}

/**
 * 运行期抽样：前 [sampleSize] 张参与判断的卡片里有几张读出了发布时间。
 *
 * 35 个宿主版本都没有结构化发布时间，文案里有没有日期只能在真机上看。读取链装上了
 * 不等于能命中——全部读不出时必须明确报出来，不能让"安装成功"掩盖"从不命中"。
 * 只计数、不记文案内容；样本满后只回报一次。
 */
internal class PublishTimeParseProbe(private val sampleSize: Int = 60) {
    private val seen = AtomicInteger()
    private val parsed = AtomicInteger()
    private val reported = AtomicBoolean()

    data class Sample(val parsed: Int, val seen: Int)

    fun record(hit: Boolean): Sample? {
        if (reported.get()) return null
        if (hit) parsed.incrementAndGet()
        val count = seen.incrementAndGet()
        if (count < sampleSize || !reported.compareAndSet(false, true)) return null
        return Sample(parsed.get(), count)
    }
}
