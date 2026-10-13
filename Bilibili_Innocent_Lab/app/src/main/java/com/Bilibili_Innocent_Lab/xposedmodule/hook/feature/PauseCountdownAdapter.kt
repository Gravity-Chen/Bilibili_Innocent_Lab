package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 专用广告倒计时的正向门禁；stock/普通 bar、恢复和已取消协程全部交还宿主。 */
internal object PauseCountdownAdapter {
    private const val SOURCE = "kntr.common.ad.source.IKSourceContent"
    private const val REPORTABLE = "kntr.common.ad.capability.report.IReportable"

    class Access internal constructor(val entry: Method, private val label: Field,
        private val service: Field, private val source: Field, private val captured: Field?,
        private val sourceType: Class<*>, private val hasAd: Method, private val context: Method,
        private val ensureActive: Method, private val unit: Any) {
        fun allows(receiver: Any?, input: Any?): Boolean = runCatching {
            if (receiver == null || input !== unit || label.getInt(receiver) != 0) return@runCatching false
            val coroutineContext = context.invoke(receiver) ?: return@runCatching false
            // ensureActive 抛出时不拦截；宿主原方法继续交付自己的取消异常。
            ensureActive.invoke(null, coroutineContext)
            val ad = if (captured != null) captured.get(receiver) else {
                val owner = service.get(receiver) ?: return@runCatching false
                source.get(owner)
            } ?: return@runCatching false
            sourceType.isInstance(ad) && hasAd.invoke(ad) as? Boolean == true
        }.getOrDefault(false)

        fun emptyResult(): Any = unit
    }

    fun resolve(loader: ClassLoader, owner: Class<*>, service: Class<*>): Access? = runCatching {
        val source = KavaMemberLookup.classOrNull(loader, SOURCE) ?: return null
        val reportable = KavaMemberLookup.classOrNull(loader, REPORTABLE)
        val contextType = KavaMemberLookup.classOrNull(loader, "kotlin.coroutines.CoroutineContext") ?: return null
        val job = KavaMemberLookup.classOrNull(loader, "kotlinx.coroutines.JobKt") ?: return null
        val active = KavaMemberLookup.inheritedMethodOrNull(job, "ensureActive", contextType)
            ?.takeIf { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE } ?: return null
        val unitType = KavaMemberLookup.classOrNull(loader, "kotlin.Unit") ?: return null
        val unit = unitType.getField("INSTANCE").get(null) ?: return null
        resolve(owner, service, source, reportable, contextType, active, unit)
    }.getOrNull()

    internal fun resolve(owner: Class<*>, service: Class<*>, source: Class<*>, reportable: Class<*>?,
        contextType: Class<*>, ensureActive: Method, unit: Any): Access? = runCatching {
        val entry = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it.name == "invokeSuspend" && it.returnType == Any::class.java &&
                it.parameterTypes.contentEquals(arrayOf(Any::class.java))
        }.singleOrNull() ?: return null
        fun field(type: Class<*>, predicate: (Field) -> Boolean) =
            KavaMemberLookup.declaredFields(type, makeAccessible = true) {
                !Modifier.isStatic(it.modifiers) && predicate(it)
            }.singleOrNull()
        val label = field(owner) { it.name == "label" && it.type == Int::class.javaPrimitiveType } ?: return null
        val capturedService = field(owner) { it.name == "this\$0" && it.type == service } ?: return null
        val sourceField = field(service) { it.type == source } ?: return null
        val reportFields = if (reportable == null) emptyList() else KavaMemberLookup.declaredFields(owner,
            makeAccessible = true) { !Modifier.isStatic(it.modifiers) && it.type == reportable }
        if (reportFields.size > 1) return null
        val hasAd = KavaMemberLookup.methodOrNull(source, "getHasAdContent")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType } ?: return null
        val context = KavaMemberLookup.inheritedMethodOrNull(owner, "getContext")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                contextType.isAssignableFrom(it.returnType) } ?: return null
        Access(entry, label, capturedService, sourceField, reportFields.singleOrNull(), source,
            hasAd, context, ensureActive, unit)
    }.getOrNull()

    internal fun requestFamilies(carriers: Collection<String>): Set<String> = carriers.map { name -> when {
        name.startsWith("kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi") -> "ad-api"
        name.startsWith("com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService") -> "theseus"
        name == "kntr.app.ad.biz.videodetail.pausedpage.ui.g" -> "legacy-ui"
        else -> "unverified-request"
    } }.toSet()
}
