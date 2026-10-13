package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 原生 Binding / 旧 Widget / Compose 独立按钮；不接管回复动作或修改评论数据。 */
internal class CommentReplyButtonFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    @SuppressLint("DiscouragedApi") // 宿主资源不属于模块 R；每份 Binding 只解析一次。
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        var installed = 0
        var expected = 0
        val coverage = CommentFamilyCoverage()
        val replyIds = mutableMapOf<Class<*>, Int>()
        for (family in FAMILIES) {
            val owners = CommentReplyButtonLocator.ownerNames(family)
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            if (owners.isEmpty()) continue
            val points = owners.flatMap(CommentReplyButtonLocator::nativePoints).distinctBy { it.method }
            expected += points.size.coerceAtLeast(1)
            var familyInstalled = 0
            for ((index, point) in points.withIndex()) {
                val method = point.method
                runCatching {
                    environment.registrar.exact("comment.reply_button.${method.declaringClass.name}.${method.name}.$index",
                        method.declaringClass, method.name, *method.parameterTypes) {
                        after {
                            if (hasThrowable) return@after
                            val binding = argOrNull(point.bindingIndex) ?: return@after
                            val root = point.rootGetter.invoke(binding) as? View ?: return@after
                            val replyId = replyIds.getOrPut(binding.javaClass) {
                                root.resources.getIdentifier("reply_button", "id", TARGET_PACKAGE)
                            }
                            if (replyId <= 0) return@after
                            val button = root.findViewById<View>(replyId) ?: return@after
                            hide(button, environment)
                        }
                    }
                    familyInstalled++
                }.onFailure {
                    environment.logError("comment_reply_button_register", "[BIL] 评论回复入口 Hook 注册失败: $it")
                }
            }
            installed += familyInstalled
            coverage.record(family.removePrefix("com.bilibili.app.comment3.ui.").removeSuffix(".handle"),
                outcome(familyInstalled, points.size))
        }
        // action4 不具有全局语义，只按专用 Widget 的绑定接口识别。
        KavaMemberLookup.classOrNull(loader, CommentReplyButtonLocator.LEGACY_WIDGET)?.let { widget ->
            val methods = CommentReplyButtonLocator.widgetMethods(widget)
            expected += methods.size.coerceAtLeast(1)
            var widgetInstalled = 0
            for (method in methods) {
                runCatching {
                    environment.registrar.exact("comment.reply_button.widget.${method.name}",
                        widget, method.name, *method.parameterTypes) {
                        after {
                            if (!hasThrowable) (instance as? View)?.let { hide(it, environment) }
                        }
                    }
                    widgetInstalled++
                }.onFailure {
                    environment.logError("comment_reply_button_widget", "[BIL] 评论回复 Widget Hook 注册失败: $it")
                }
            }
            installed += widgetInstalled
            coverage.record("widget", outcome(widgetInstalled, methods.size))
        }
        val composeType = KavaMemberLookup.classOrNull(loader, CommentReplyButtonLocator.BUTTON_TYPE)
        if (composeType != null || KavaMemberLookup.hasClass(loader, CommentReplyButtonLocator.COMPOSE_MODEL)) {
            val renderer = KavaMemberLookup.classOrNull(loader, CommentReplyButtonLocator.COMPOSE_RENDERER)
            val hasReply = composeType?.enumConstants.orEmpty().any {
                CommentReplyButtonLocator.isReply(it, requireNotNull(composeType))
            }
            val methods = if (renderer != null && composeType != null && hasReply)
                CommentReplyButtonLocator.composeMethods(renderer, composeType) else emptyList()
            expected += methods.size.coerceAtLeast(1)
            var composeInstalled = 0
            for ((index, method) in methods.withIndex()) {
                runCatching {
                    environment.registrar.exact("comment.reply_button.compose.${method.name}.$index",
                        method.declaringClass, method.name, *method.parameterTypes) {
                        before {
                            if (!CommentReplyButtonLocator.isReply(argOrNull(0), requireNotNull(composeType))) return@before
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            // 在 RenderButton 的 Compose group 开始前返回，只省略 REPLY 子组件。
                            result = null
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                    composeInstalled++
                }.onFailure {
                    environment.logError("comment_reply_button_compose", "[BIL] Compose 回复按钮 Hook 注册失败: $it")
                }
            }
            installed += composeInstalled
            coverage.record("compose", outcome(composeInstalled, methods.size))
        }
        if (installed == 0) return FeatureInstallResult.Skipped("missing-comment-binding")
        val complete = coverage.isComplete()
        environment.logInfo("comment_reply_button_installed",
            "[BIL] 隐藏评论回复入口已安装: $installed/$expected, complete=$complete, ${coverage.describe()}")
        return FeatureInstallResult.Installed(installed, complete)
    }

    private fun outcome(installed: Int, expected: Int): FeatureInstallResult =
        if (installed == 0) FeatureInstallResult.Skipped("missing-comment-binding")
        else FeatureInstallResult.Installed(installed, installed == expected)

    private fun hide(button: View, environment: HookEnvironment) {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        if (button.visibility != View.GONE) {
            button.visibility = View.GONE
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        }
    }

    companion object {
        const val ID = "comment_reply_button"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private val FAMILIES = listOf("holder", "nextholder", "nextholderexp3")
            .map { "com.bilibili.app.comment3.ui.$it.handle" }
    }
}
