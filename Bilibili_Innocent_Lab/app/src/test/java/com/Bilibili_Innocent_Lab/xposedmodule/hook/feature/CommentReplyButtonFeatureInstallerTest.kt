package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.app.comment3.data.model.CommentItem
import com.bilibili.app.comment3.ui.holder.handle.ReplyButtonTestHandler
import kntr.common.comment.card.model.comment.ActionBarButtonType
import kntr.common.comment.card.model.comment.ReplyButtonTestModel
import kntr.common.comment.card.renderer.comment.CommentActionBarRenderer
import org.junit.Assert.*
import org.junit.Test

class CommentReplyButtonFeatureInstallerTest {
    class Binding { @Suppress("unused") fun getRoot(): View? = null }
    class WrongRoot { @Suppress("unused") fun getRoot(): String = "not a view" }
    @Suppress("UNUSED_PARAMETER")
    class OldHandler {
        fun bind(model: CommentItem, binding: Binding, subject: Any, renamedContext: Any, index: Int) = Unit
        fun resized(model: CommentItem, size: Int, binding: Binding) = Unit
        fun click(model: CommentItem, context: Any) = Unit
        fun wrong(model: CommentItem, binding: WrongRoot) = Unit
        fun ambiguous(model: CommentItem, first: Binding, second: Binding) = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class CapturedHandler(@JvmField val model: CommentItem) {
        fun render(binding: Binding) = Unit
        fun update(binding: Binding, expanded: Boolean) = Unit
        fun unrelated(value: String) = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class UnrelatedHandler { fun render(binding: Binding) = Unit }
    class RenamedModel
    interface BindContract { fun renamed(model: RenamedModel) }
    interface InheritedContract : BindContract
    @Suppress("UNUSED_PARAMETER")
    class LegacyWidget : InheritedContract {
        override fun renamed(model: RenamedModel) = Unit
        fun setter(value: String) = Unit
        fun onClick(view: View) = Unit
    }
    private enum class ForeignButton { REPLY }

    @Test fun nativeBindingIgnoresContextNamesAndFindsTheActualBindingSlot() {
        val points = CommentReplyButtonLocator.nativePoints(OldHandler::class.java).associateBy { it.method.name }
        assertEquals(setOf("bind", "resized"), points.keys)
        assertEquals(1, points.getValue("bind").bindingIndex)
        assertEquals(2, points.getValue("resized").bindingIndex)
    }

    @Test fun capturedCommentModelsSupportNewOneArgumentBindingsButDoNotExpandToUnrelatedViews() {
        val points = CommentReplyButtonLocator.nativePoints(CapturedHandler::class.java)
        assertEquals(setOf("render", "update"), points.map { it.method.name }.toSet())
        assertTrue(points.all { it.bindingIndex == 0 })
        assertTrue(CommentReplyButtonLocator.nativePoints(UnrelatedHandler::class.java).isEmpty())
    }

    @Test fun legacyWidgetUsesItsBindingInterfaceRatherThanObfuscatedParameterPackages() {
        assertEquals(listOf("renamed"), CommentReplyButtonLocator.widgetMethods(LegacyWidget::class.java).map { it.name })
    }

    @Test fun scopedModernActionHandlersDoNotNeedToRetainTheWholeCommentModel() {
        val points = CommentReplyButtonLocator.nativePoints(ReplyButtonTestHandler::class.java)
        assertEquals(setOf("bind", "reset"), points.map { it.method.name }.toSet())
        assertTrue(points.all { it.bindingIndex == 0 })
    }

    @Test fun composeVariantsIncludePrivateEarlyAndResizedLatestButtonsWithoutSelectingTheWholeBar() {
        val methods = CommentReplyButtonLocator.composeMethods(CommentActionBarRenderer::class.java, ActionBarButtonType::class.java)
        assertEquals(setOf(6, 10, 11), methods.map { it.parameterCount }.toSet())
        assertEquals(setOf("I", "f"), methods.map { it.name }.toSet())
        assertTrue(CommentReplyButtonLocator.isReply(ActionBarButtonType.REPLY, ActionBarButtonType::class.java))
        assertFalse(CommentReplyButtonLocator.isReply(ForeignButton.REPLY, ActionBarButtonType::class.java))
        assertFalse(CommentReplyButtonLocator.isReply("REPLY", ActionBarButtonType::class.java))
        assertFalse(CommentReplyButtonLocator.isReply(null, ActionBarButtonType::class.java))
    }

    @Test fun realHookDslSkipsOnlyReplyAndPreservesAllOtherComposeButtons() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val installed = CommentReplyButtonFeatureInstaller(true).install(environment(registrar, stages))
        assertEquals(FeatureInstallResult.Installed(3), installed)
        for ((id, entry) in registrar.hooks) {
            for (type in ActionBarButtonType.values()) {
                val args = entry.member.parameterTypes.map { parameter -> when (parameter) {
                    ActionBarButtonType::class.java -> type
                    ReplyButtonTestModel::class.java -> ReplyButtonTestModel()
                    Boolean::class.javaPrimitiveType -> false
                    Long::class.javaPrimitiveType -> 0L
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                } }.toTypedArray()
                var originalCalls = 0
                registrar.invoke(id, CommentActionBarRenderer(), args) { originalCalls++; null }
                assertEquals(if (type == ActionBarButtonType.REPLY) 0 else 1, originalCalls)
            }
        }
        assertEquals(3, stages.count { it == FeatureRuntimeStage.APPLIED })
    }

    @Test fun oneFailedComposeVariantKeepsCoveragePartial() {
        val methods = CommentReplyButtonLocator.composeMethods(CommentActionBarRenderer::class.java, ActionBarButtonType::class.java)
        val failed = "comment.reply_button.compose.I.${methods.indexOfFirst { it.name == "I" }}"
        val result = CommentReplyButtonFeatureInstaller(true).install(environment(PlayerPortTestRegistrar(failed)))
        assertEquals(FeatureInstallResult.Installed(2, complete = false), result)
    }

    @Test fun disabledAndSecondaryProcessesInstallNothing() {
        val registrar = PlayerPortTestRegistrar()
        val env = environment(registrar)
        assertEquals(FeatureInstallResult.Skipped("disabled"), CommentReplyButtonFeatureInstaller(false).install(env))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            CommentReplyButtonFeatureInstaller(true).install(env.copy(processName = "tv.danmaku.bili:download")))
        assertTrue(registrar.hooks.isEmpty())
    }

    private fun environment(registrar: PlayerPortTestRegistrar, stages: MutableList<FeatureRuntimeStage> = mutableListOf()) =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
            registrar, { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage })
}
