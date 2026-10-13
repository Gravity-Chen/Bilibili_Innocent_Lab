package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 在评论 Binding 更新完成后隐藏独立回复入口，不改评论点击动作和楼中楼模型。 */
internal class CommentReplyButtonFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    @SuppressLint("DiscouragedApi") // 宿主资源不属于模块 R；每份 Binding 只解析一次。
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        var installed = 0
        var expected = 0
        val roots = mutableMapOf<Class<*>, Method?>()
        val replyIds = mutableMapOf<Class<*>, Int>()
        for (family in FAMILIES) {
            val owners = (('a'..'z').map { "$family.$it" } +
                ('a'..'z').map { "$family.${it}0" } +
                CommentNativeBindingCatalog.mainHandlers.filter { it.startsWith("$family.") })
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            for (owner in owners) {
                for (method in bindingMethods(owner)) {
                    val bindingClass = method.parameterTypes[1]
                    val rootGetter = roots.getOrPut(bindingClass) {
                        KavaMemberLookup.methodOrNull(bindingClass, "getRoot")
                            ?.takeIf { it.returnType isSubclassOf classOf<View>() }
                    } ?: continue
                    expected++
                    runCatching {
                        environment.registrar.exact("comment.reply_button.${owner.name}.${method.name}",
                            owner, method.name, *method.parameterTypes) {
                            after {
                                if (hasThrowable) return@after
                                val binding = argOrNull(1) ?: return@after
                                val root = rootGetter.invoke(binding) as? View ?: return@after
                                val replyId = replyIds.getOrPut(bindingClass) {
                                    root.resources.getIdentifier("reply_button", "id", TARGET_PACKAGE)
                                }
                                if (replyId <= 0) return@after
                                val button = root.findViewById<View>(replyId) ?: return@after
                                hide(button, environment)
                            }
                        }
                        installed++
                    }.onFailure {
                        environment.logError("comment_reply_button_register", "[BIL] 评论回复入口 Hook 注册失败: $it")
                    }
                }
            }
        }
        // comment2 的资源名 action4 不具有全局语义，只按专用 Widget 类识别。
        KavaMemberLookup.classOrNull(loader, LEGACY_WIDGET)?.let { widget ->
            val methods = KavaMemberLookup.declaredMethods(widget).filter {
                !it.isStatic && !it.isSynthetic && it.returnType == Void.TYPE &&
                    it.parameterCount == 1 && it.parameterTypes[0].name.startsWith("ws.")
            }
            expected += methods.size.coerceAtLeast(1)
            methods.forEach { method ->
                runCatching {
                    environment.registrar.exact("comment.reply_button.widget.${method.name}",
                        widget, method.name, *method.parameterTypes) {
                        after {
                            if (!hasThrowable) (instance as? View)?.let { hide(it, environment) }
                        }
                    }
                    installed++
                }.onFailure {
                    environment.logError("comment_reply_button_widget", "[BIL] 评论回复 Widget Hook 注册失败: $it")
                }
            }
        }
        if (installed == 0) return FeatureInstallResult.Skipped("missing-comment-binding")
        // 新 Compose 评论不能由原生 Binding 的安装结果冒充完整适配。
        val hasCompose = KavaMemberLookup.hasClass(loader, "kntr.common.comment.card.model.comment.CommentModel")
        val complete = installed == expected && !hasCompose
        environment.logInfo("comment_reply_button_installed", "[BIL] 隐藏评论回复入口已安装: $installed/$expected, complete=$complete")
        return FeatureInstallResult.Installed(installed, complete)
    }

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
        private const val LEGACY_WIDGET = "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget"
        private val FAMILIES = listOf("holder", "nextholder", "nextholderexp3")
            .map { "com.bilibili.app.comment3.ui.$it.handle" }

        /** 只接入卡片绑定签名；点击/点赞处理方法和 bridge 方法不属于此边界。 */
        internal fun bindingMethods(owner: Class<*>): List<Method> =
            KavaMemberLookup.declaredMethods(owner).filter {
                !it.isStatic && !it.isBridge && !it.isSynthetic && it.returnType == Void.TYPE &&
                    it.parameterCount == 5 &&
                    it.parameterTypes[0].name == "com.bilibili.app.comment3.data.model.CommentItem" &&
                    it.parameterTypes[3].name == "com.bilibili.app.comment3.ui.t" &&
                    it.parameterTypes[4] == classOf<Int>()
            }
    }
}
