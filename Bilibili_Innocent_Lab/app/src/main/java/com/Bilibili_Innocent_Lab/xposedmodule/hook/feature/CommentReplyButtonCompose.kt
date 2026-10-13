package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

internal fun commentReplyButtonRenderer(
    owner: Class<*>,
    buttonType: Class<*>,
    composer: Class<*>
): Method? = KavaMemberLookup.declaredMethods(owner).singleOrNull { method ->
    !method.isStatic && !method.isBridge && !method.isSynthetic &&
        method.returnType == Void.TYPE && method.parameterTypes.firstOrNull() == buttonType &&
        method.parameterTypes.count { it == composer } == 1
}

/** 在宿主打开 Compose 分组前跳过独立 REPLY 分支，不触碰操作栏模型或其它按钮。 */
internal object CommentReplyButtonCompose {
    fun install(environment: HookEnvironment): FeatureInstallResult? {
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val owner = KavaMemberLookup.classOrNull(loader, RENDERER)
        val buttonType = KavaMemberLookup.classOrNull(loader, BUTTON_TYPE)
        if (owner == null && buttonType == null &&
            KavaMemberLookup.classOrNull(loader, COMMENT_MODEL) == null
        ) return null
        val reply = buttonType?.enumConstants?.singleOrNull { (it as? Enum<*>)?.name == "REPLY" }
        val composer = KavaMemberLookup.classOrNull(loader, COMPOSER)
        val method = if (owner != null && buttonType != null && composer != null) {
            commentReplyButtonRenderer(owner, buttonType, composer)
        } else null
        if (reply == null || method == null) return FeatureInstallResult.Skipped("missing-compose-reply-renderer")
        return runCatching {
            environment.registrar.exact(
                "comment.reply_button.compose",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                before {
                    if (argOrNull(0) !== reply) return@before
                    environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                    result = null
                    environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                }
            }
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.logError("comment_reply_button_compose", "[BIL] 评论回复按钮 Compose Hook 注册失败: $throwable")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    private const val RENDERER = "kntr.common.comment.card.renderer.comment.CommentActionBarRenderer"
    private const val BUTTON_TYPE = "kntr.common.comment.card.model.comment.ActionBarButtonType"
    private const val COMMENT_MODEL = "kntr.common.comment.card.model.comment.CommentModel"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val CAPABILITY = "comments_reply_button_removed"
}
