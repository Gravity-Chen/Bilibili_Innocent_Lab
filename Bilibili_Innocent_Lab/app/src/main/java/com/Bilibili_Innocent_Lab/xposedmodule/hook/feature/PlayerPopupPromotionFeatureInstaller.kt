package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** 广告列表与自动免流促销独立安装；后台适配完成后只补此前缺失的入口。 */
internal class PlayerPopupPromotionFeatureInstaller(
    private val enabled: Boolean,
    private val cachedPoints: () -> PlayerPopupPromotionPoints? = { null }
) : FeatureInstaller {
    override val id = ID
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registered = linkedSetOf<Method>()
    private val attempted = linkedSetOf<Method>()
    private val errorLogged = AtomicBoolean(false)
    private val unitMissingLogged = AtomicBoolean(false)

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        if (environment.processName != "tv.danmaku.bili") return skipped(environment, "non-main-process")
        val loader = environment.classLoader ?: return skipped(environment, "missing-host-structure")
        val cached = cachedPoints()
        val configReaders = runCatching { PlayerFreeDataPromotionConfigAccess.readers(loader) }.getOrDefault(emptyList())
        val access = runCatching { PlayerPopupPromotionLocator.resolve(loader, cached?.advertFloat) }.getOrNull()
        val guide = runCatching {
            val point = PlayerPopupPromotionAdapter.direct(loader).freeDataGuide ?: cached?.freeDataGuide
            PlayerPopupPromotionAdapter.resolveFreeDataGuide(loader, point)
        }.getOrNull()
        val freeData = PlayerFreeDataPromotionAccess.resolve(loader)
        val storyApplicable = PlayerStoryFreeDataPromotionAccess.applicable(loader)
        val story = runCatching { PlayerStoryFreeDataPromotionAccess.resolve(loader) }.getOrNull()
        val hostUnit = KavaMemberLookup.classOrNull(loader, "kotlin.Unit")?.let { unit ->
            KavaMemberLookup.fieldOrNull(unit, "INSTANCE")?.takeIf { Modifier.isStatic(it.modifiers) && it.type == unit }
                ?.let { runCatching { it.get(null) }.getOrNull()?.takeIf(unit::isInstance) }
        }
        if (guide != null && hostUnit == null && unitMissingLogged.compareAndSet(false, true))
            environment.logError("player_popup_promotion_missing_unit", "[BIL] 宿主 Unit 返回值缺失，已保留自动推广流程；其他防线继续安装")
        requiresAdaptationRetry = access == null || access.readers.size < PlayerPopupPromotionLocator.READERS.size || guide == null
        for (reader in configReaders) {
            if (!attempted.add(reader)) continue
            runCatching {
                environment.registrar.exact("player.popup.promotion.config.${reader.declaringClass.simpleName}",
                    reader.declaringClass, reader.name, *reader.parameterTypes) {
                    before {
                        // 全局配置读取的未命中路径只比较 key；不能读取整份参数或配置。
                        val key = argOrNull(0)
                        if (key != PlayerFreeDataPromotionConfigAccess.KEY ||
                            !PlayerFreeDataPromotionConfigAccess.matches(key, argOrNull(1))) return@before
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        result = "0"
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                registered += reader
            }.onFailure {
                environment.logError("player_popup_promotion_config_${reader.declaringClass.simpleName}", "[BIL] 自动免流推广开关入口注册失败")
            }
        }
        val readCardType = access?.let { it::cardType }
        for (reader in access?.readers.orEmpty()) {
            if (!attempted.add(reader)) continue
            runCatching {
                environment.registrar.exact("player.popup.promotion.${reader.name}", reader.declaringClass, reader.name) {
                    after {
                        if (hasThrowable) return@after
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        // 任一字段读取失败时保留整份原列表，不发布半份过滤结果。
                        val updated = runCatching {
                            PlayerPopupPromotionPolicy.filter(source, readCardType!!)
                        }.getOrElse {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                            if (errorLogged.compareAndSet(false, true)) environment.logError(
                                "player_popup_promotion_read", "[BIL] 播放器推广列表读取失败或超限，已保留原列表")
                            return@after
                        }
                        if (updated !== source) {
                            result = updated
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, source.size - updated.size)
                        }
                    }
                }
                registered += reader
            }.onFailure {
                environment.logError("player_popup_promotion_${reader.name}", "[BIL] 播放器推广过滤入口注册失败")
            }
        }
        if (guide != null && hostUnit != null && attempted.add(guide)) {
            runCatching {
                environment.registrar.exact("player.popup.promotion.free-data-guide", guide.declaringClass, guide.name,
                    *guide.parameterTypes) {
                    before {
                        // 只拦自动促销流程；它在请求 TFInfo 之前就有 Unit 早退分支。
                        // 同类中的蜂窝流量、已免流状态、计费失败提醒保留宿主实现。
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        result = hostUnit
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                registered += guide
            }.onFailure {
                environment.logError("player_popup_promotion_free_data_guide", "[BIL] 自动免流推广入口注册失败")
            }
        }
        if (freeData != null && attempted.add(freeData.reader)) {
            runCatching {
                environment.registrar.exact("player.popup.promotion.tf-toast", freeData.reader.declaringClass, freeData.reader.name) {
                    after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val promotion = runCatching { freeData.isPromotion(original) }.getOrElse {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                            if (errorLogged.compareAndSet(false, true)) environment.logError(
                                "player_popup_promotion_tf_read", "[BIL] 免流推广字段读取失败，已保留原数据")
                            return@after
                        }
                        if (promotion) {
                            // 不改 protobuf 或进程级 DEFAULT_INSTANCE；下游按空标题不展示推广。
                            result = freeData.emptyToast
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                registered += freeData.reader
            }.onFailure {
                environment.logError("player_popup_promotion_tf_toast", "[BIL] 免流推广数据入口注册失败")
            }
        }
        if (story != null && attempted.add(story.reader)) {
            runCatching {
                environment.registrar.exact("player.popup.promotion.story-free-data-toast", story.reader.declaringClass, story.reader.name) {
                    after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        if (!story.isPromotion(original)) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        // 宿主 collectLatest 的原有 null 分支更新状态并清理视图，不创建推广。
                        result = null
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                registered += story.reader
            }.onFailure {
                environment.logError("player_popup_promotion_story", "[BIL] Story 免流推广数据入口注册失败")
            }
        }
        val installed = registered.size
        if (installed == 0) return skipped(environment, "no-safe-hook-point")
        val expected = PlayerPopupPromotionLocator.READERS.size + 2 + PlayerFreeDataPromotionConfigAccess.OWNERS.size +
            if (storyApplicable) 1 else 0
        environment.reportStatus(STATUS, if (installed == expected) "success" else "partial:$installed/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_popup_promotion"
        private const val STATUS = "player_popup_promotion_status"
    }
}
