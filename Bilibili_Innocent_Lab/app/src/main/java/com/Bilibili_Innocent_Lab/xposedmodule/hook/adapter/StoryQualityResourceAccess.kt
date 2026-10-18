package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** 仅用于已确认属于本代私有 resolve 的资源；安装期解析成员，提案与写入由会话 lease 分开保护。 */
internal class StoryQualityResourceAccess private constructor(
    private val resourceClass: Class<*>, private val indexClass: Class<*>,
    private val source: Method, private val flashSource: Int, private val normalSource: Int,
    private val getIndex: Method, private val getResolved: Method, private val setResolved: Method,
    private val findAvailable: Method, private val pairFirst: Method, private val pairSecond: Method,
    private val special: List<Method>, private val specialQns: Set<Int>,
    private val vod: Field, private val list: Field, private val quality: Field,
    private val needLogin: Field, private val needVip: Field, private val drm: Field, private val noDrm: Any,
    private val playError: Field?, private val invalid: Field?,
    private val canWatch: Field?, private val defaultCanWatch: Any?,
    private val limitWatch: Field?, private val defaultLimitWatch: Any?
) {
    class Candidate internal constructor(
        val index: Int, val qn: Int, internal val oldIndex: Int, internal val target: Int,
        internal val login: Boolean, internal val vip: Boolean,
        internal val access: Any, internal val resource: WeakReference<Any>,
        internal val vod: WeakReference<Any>, internal val list: WeakReference<Any>,
        internal val selected: WeakReference<Any>, internal val original: WeakReference<Any>,
        internal val consumed: AtomicBoolean = AtomicBoolean(false)
    )

    private val identity = Any()
    private data class View(val vod: Any, val values: List<*>, val size: Int, val oldIndex: Int, val original: Any)

    fun isPreloaded(resource: Any): Boolean = runCatching {
        resourceClass.isInstance(resource) && source.invoke(resource) == flashSource
    }.getOrDefault(false)

    fun currentQuality(resource: Any): Int? = runCatching {
        if (!resourceClass.isInstance(resource)) return null
        val current = getIndex.invoke(resource)?.takeIf(indexClass::isInstance) ?: return null
        quality.getInt(current).takeIf { it > 0 }
    }.getOrNull()

    /** null 表示未知，调用方不能将其当作普通资源并触发重新解析。 */
    fun isSpecial(resource: Any): Boolean? = runCatching {
        if (!resourceClass.isInstance(resource)) return null
        special.any { it.invoke(resource) as Boolean }
    }.getOrNull()

    fun currentEligible(resource: Any, login: Boolean, vip: Boolean): Boolean = runCatching {
        val view = view(resource) ?: return false
        eligible(view.original, login, vip)
    }.getOrDefault(false)

    /** 只证明当前已解析资源内的最佳合法档，不证明服务端没有更高画质。 */
    fun accepted(resource: Any, target: Int, login: Boolean, vip: Boolean): Boolean =
        proposal(resource, target, login, vip, allowSame = true)?.let { it.index == it.oldIndex } == true

    /**
     * 不改原资源；先筛权利再让宿主校准可用 index。本人视频例外保留给原 Service，不自行放宽。
     * findMaxAvailableQuality 只检查地址/DASH，因此返回项还必须重新通过全部权利判据。
     */
    fun selection(resource: Any, target: Int, login: Boolean, vip: Boolean, ownVideo: Boolean = false): Candidate? =
        if (ownVideo) null else proposal(resource, target, login, vip, allowSame = false)

    private fun proposal(resource: Any, target: Int, login: Boolean, vip: Boolean, allowSame: Boolean): Candidate? =
        runCatching {
            if (target <= 0) return null
            val view = view(resource) ?: return null
            val (vodValue, values, size, oldIndex, original) = view
            if (!eligible(original, login, vip)) return null
            var ceiling = 0
            for (position in 0 until size) {
                val entry = values[position] ?: return null
                if (!indexClass.isInstance(entry)) return null
                val qn = quality.getInt(entry)
                if (qn in 1..target && eligible(entry, login, vip) && qn > ceiling) ceiling = qn
            }
            if (ceiling == 0) return null
            val pair = findAvailable.invoke(resource, ceiling) ?: return null
            val selectedIndex = pairFirst.invoke(pair) as? Int ?: return null
            val selectedQn = pairSecond.invoke(pair) as? Int ?: return null
            if (values.size != size || selectedIndex !in 0 until size || (!allowSame && selectedIndex == oldIndex) ||
                selectedQn !in 1..ceiling) return null
            val selected = values[selectedIndex] ?: return null
            if (quality.getInt(selected) != selectedQn || !eligible(selected, login, vip) ||
                getResolved.invoke(resource) != oldIndex || getIndex.invoke(resource) !== original) return null
            Candidate(selectedIndex, selectedQn, oldIndex, target, login, vip, identity, WeakReference(resource),
                WeakReference(vodValue), WeakReference(values), WeakReference(selected), WeakReference(original))
        }.getOrNull()

    private fun view(resource: Any): View? {
        if (!resourceClass.isInstance(resource) || isSpecial(resource) != false) return null
        val from = source.invoke(resource) as Int
        if (from != flashSource && from != normalSource) return null
        val vodValue = vod.get(resource) ?: return null
        val values = list.get(vodValue) as? List<*> ?: return null
        val size = values.size
        if (size == 0 || size > MAX_ITEMS) return null
        val oldIndex = getResolved.invoke(resource) as Int
        if (oldIndex !in 0 until size) return null
        val original = getIndex.invoke(resource) ?: return null
        if (!indexClass.isInstance(original) || values[oldIndex] !== original) return null
        return View(vodValue, values, size, oldIndex, original)
    }

    /** 调用方在此之前重新核会话/账户准入；本方法再次核资源身份、权限字段及原 index，写后回读。 */
    fun apply(resource: Any, candidate: Candidate): Boolean {
        if (candidate.access !== identity || candidate.resource.get() !== resource ||
            !candidate.consumed.compareAndSet(false, true)) return false
        val fresh = selection(resource, candidate.target, candidate.login, candidate.vip) ?: return false
        if (fresh.index != candidate.index || fresh.qn != candidate.qn || fresh.oldIndex != candidate.oldIndex ||
            fresh.vod.get() !== candidate.vod.get() || fresh.list.get() !== candidate.list.get() ||
            fresh.selected.get() !== candidate.selected.get() || fresh.original.get() !== candidate.original.get()) return false
        return runCatching {
            setResolved.invoke(resource, candidate.index)
            check(getResolved.invoke(resource) == candidate.index && getIndex.invoke(resource) === candidate.selected.get())
            true
        }.getOrElse {
            // setter 可能先写入再抛错；即使 invoke 抛错也尽力恢复，失败不伪装成功。
            runCatching { setResolved.invoke(resource, candidate.oldIndex) }
            false
        }
    }

    private fun eligible(entry: Any, login: Boolean, vip: Boolean): Boolean {
        if (!indexClass.isInstance(entry) || drm.get(entry) !== noDrm || quality.getInt(entry) <= 0 ||
            quality.getInt(entry) in specialQns || (needLogin.getBoolean(entry) && !login) ||
            (needVip.getBoolean(entry) && !vip)) return false
        if (playError?.get(entry) != null || invalid?.getBoolean(entry) == true) return false
        val can = canWatch?.get(entry)
        if (can != null && can !== defaultCanWatch) return false
        val limit = limitWatch?.get(entry)
        return limit == null || limit === defaultLimitWatch
    }

    companion object {
        private const val PREFIX = "com.bilibili.lib.media.resource."
        private const val MAX_ITEMS = 128

        fun resolve(loader: ClassLoader): StoryQualityResourceAccess? {
            val resource = Lookup.classOrNull(loader, PREFIX + "MediaResource") ?: return null
            val vod = Lookup.classOrNull(loader, PREFIX + "VodIndex") ?: return null
            val index = Lookup.classOrNull(loader, PREFIX + "PlayIndex") ?: return null
            return resolve(resource, vod, index)
        }

        /** 测试使用同样的成员验真，不以 fixture 名称代替实际 ABI。 */
        internal fun resolve(resource: Class<*>, vodIndex: Class<*>, playIndex: Class<*>): StoryQualityResourceAccess? =
            runCatching {
                val int = Int::class.javaPrimitiveType!!
                val bool = Boolean::class.javaPrimitiveType!!
                fun field(owner: Class<*>, name: String, type: Class<*>? = null): Field? =
                    Lookup.fieldOrNull(owner, name)?.takeIf { !Modifier.isStatic(it.modifiers) && (type == null || it.type == type) }
                fun constant(owner: Class<*>, name: String): Int? = Lookup.fieldOrNull(owner, name)?.takeIf {
                    Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == int
                }?.getInt(null)
                fun enumConstant(owner: Class<*>, name: String): Any? =
                    Lookup.fieldOrNull(owner, name)?.takeIf {
                        owner.isEnum && Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == owner
                    }?.get(null)
                val flash = constant(resource, "SOURCE_FROM_FLASH") ?: return null
                val normal = constant(resource, "SOURCE_FROM_NORMAL") ?: return null
                if (flash == normal) return null
                val getIndex = method(resource, "getPlayIndex", playIndex) ?: return null
                val find = Lookup.methodOrNull(resource, "findMaxAvailableQuality", int)?.takeIf {
                    !Modifier.isStatic(it.modifiers) && it.returnType.name == "kotlin.Pair"
                } ?: return null
                val first = method(find.returnType, "getFirst", Any::class.java) ?: return null
                val second = method(find.returnType, "getSecond", Any::class.java) ?: return null
                val vod = field(resource, "mVodIndex", vodIndex) ?: return null
                val list = field(vodIndex, "mVodList")?.takeIf { List::class.java.isAssignableFrom(it.type) } ?: return null
                val drm = field(playIndex, "mDrmType") ?: return null
                val noDrm = enumConstant(drm.type, "No") ?: return null
                val oldError = field(playIndex, "mPlayError")?.takeIf { !it.type.isPrimitive }
                val invalid = field(playIndex, "qualityInvalid", bool)
                val can = field(playIndex, "canWatchReason")
                val limit = field(playIndex, "limitWatchReason")
                if (oldError == null && (invalid == null || can == null || limit == null)) return null
                if ((invalid != null || can != null || limit != null) && (invalid == null || can == null || limit == null)) return null
                val defaultCan = can?.let { enumConstant(it.type, "CAN_WATCH_QN_REASON_DEFAULT") ?: return null }
                val defaultLimit = limit?.let { enumConstant(it.type, "LIMIT_WATCH_QN_REASON_DEFAULT") ?: return null }
                val specials = listOf("isHdr", "isDolbyVision", "isDrm").map { method(resource, it, bool) ?: return null }.toMutableList()
                val vividQn = constant(resource, "QUALITY_HDRVivid")
                val vivid = method(resource, "isHdrVivid", bool)
                if (vivid != null) specials += vivid else if (vividQn != null) return null
                val specialQns = listOfNotNull(constant(resource, "QUALITY_HDR"), constant(resource, "QUALITY_DOLBY"), vividQn).toSet()
                if (specialQns.size < 2 || specialQns.any { it <= 0 }) return null
                StoryQualityResourceAccess(resource, playIndex,
                    method(resource, "sourceFrom", int) ?: return null, flash, normal, getIndex,
                    method(resource, "getResolvedIndex", int) ?: return null,
                    method(resource, "setResolvedIndex", Void.TYPE, int) ?: return null,
                    find, first, second, specials, specialQns, vod, list,
                    field(playIndex, "mQuality", int) ?: return null,
                    field(playIndex, "mNeedLogin", bool) ?: return null,
                    field(playIndex, "mNeedVip", bool) ?: return null,
                    drm, noDrm, oldError, invalid, can, defaultCan, limit, defaultLimit)
            }.getOrNull()

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method? =
            Lookup.methodOrNull(owner, name, *args)?.takeIf {
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) && it.returnType == result
            }
    }
}
