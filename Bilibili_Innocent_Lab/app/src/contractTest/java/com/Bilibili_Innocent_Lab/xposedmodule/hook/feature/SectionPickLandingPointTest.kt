package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.*
import org.junit.Test

/** 不退回只覆盖首页 Fragment 或改写官方不感兴趣行为的旧落点。 */
class SectionPickLandingPointTest {
    private fun source(name: String): String = SourceContract.read("hook/feature/$name.kt")
    @Test fun nativePanelUsesSharedContentAndItemCallbacks() {
        val text = source("NativeFeedbackPanel")
        assertTrue(text.contains("kntr.app.pegasus.feedbackdialog.FeedbackDialogKt"))
        assertTrue(text.contains("BottomSheetContent"))
        assertTrue(text.contains("getOnClick"))
        assertFalse(text.contains("feedbackDialogFragmentCreate"))
    }
    @Test fun nativeDislikeNoLongerImplicitlyChangesModuleRules() {
        val text = source("SectionPickFeatureInstaller")
        assertTrue(text.contains("NativeFeedbackPanel.install"))
        assertFalse(text.contains("dislikeReasonSetter"))
        assertFalse(text.contains("feedbackItemExtendGetter"))
    }
}
