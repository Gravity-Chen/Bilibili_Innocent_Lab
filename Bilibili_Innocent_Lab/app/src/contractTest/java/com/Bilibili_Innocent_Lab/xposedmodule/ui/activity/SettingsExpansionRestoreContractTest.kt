package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class SettingsExpansionRestoreContractTest {
    @Test fun savedExpansionIsPureNamedDataAndIncludesUnbuiltCategories() {
        val save = SettingsUiSource.function("saveSettingsExpansionState")
        assertTrue(save.contains("settingsExpansionState.snapshot()"))
        assertTrue(save.contains("values.putBoolean(key, expanded)"))
        assertTrue(save.contains("putInt(\"schema\", SettingsExpansionState.SCHEMA_VERSION)"))
        assertFalse(save.contains("advancedCategorySections"))
        assertFalse(save.contains("visibility"))
        assertFalse(save.contains("rotation"))
        assertFalse(save.contains("putParcelable"))
        assertFalse(save.contains("putSerializable"))
        val factory = SettingsUiSource.function("createSettingsExpansionState")
        assertTrue(factory.contains("expansionSecondarySections.map { it.name }"))
        assertTrue(factory.contains("AdvancedSettingsCategory.entries.associate { it.name to !it.collapsible }"))
        assertFalse(factory.contains("ordinal"))
        assertTrue(SettingsUiSource.function("onSaveInstanceState").contains("saveSettingsExpansionState(outState)"))
    }

    @Test fun restorationRunsBeforeFirstPageLayoutAndBeforeScrollConsumption() {
        val create = SettingsUiSource.function("onCreate")
        val decode = create.indexOf("restoreSettingsExpansionState(savedInstanceState)")
        val mount = create.indexOf("setMeguvizContentView")
        assertTrue(decode >= 0 && mount > decode)
        val page = SettingsUiSource.function("buildSettingsPage")
        val apply = page.indexOf("applyRestoredSettingsExpansion(index)")
        assertTrue(apply > page.indexOf("with(scope)"))
        assertTrue(page.indexOf("styleHomeControls(parent)") > apply)
        val ensure = SettingsUiSource.functions(SettingsUiSource.file("SettingsHomePresenter"), "ensurePage").single()
        assertTrue(ensure.indexOf("buildPage(index, contents[index])") >= 0)
        assertTrue(ensure.indexOf("schedulePageRestore(index)") > ensure.indexOf("buildPage(index, contents[index])"))
    }

    @Test fun restoreUsesTheRealMotionSnapProtocolOnlyForTheNewPage() {
        val apply = SettingsUiSource.function("applyRestoredSettingsExpansion")
        assertTrue(apply.contains("when (index)"))
        assertTrue(apply.contains("if (category.section !in sections) return@forEach"))
        assertTrue(apply.contains("section.header.isActivated = section.expanded"))
        assertEquals(2, Regex("animate = false").findAll(apply).count())
        assertTrue(SettingsUiSource.function("animateSecondarySection")
            .contains("controller.setExpanded(expanded, animate = animate)"))
        for (forbidden in listOf("prefs(", "putBoolean(", "performClick(", "requestSettingCommand(", "applyModeledToggle(")) {
            assertFalse("Restore must not invoke business operation $forbidden", apply.contains(forbidden))
        }
        val decode = SettingsUiSource.function("restoreSettingsExpansionState")
        assertTrue(decode.contains("schema == SettingsExpansionState.SCHEMA_VERSION"))
        assertTrue(decode.contains("settingsExpansionState.snapshot().keys"))
        assertTrue(decode.contains("as? Boolean"))
    }

    @Test fun commandsReplaceLogicalTargetsBeforeSameValueOrMissingViewEarlyReturns() {
        val secondary = SettingsUiSource.function("setSecondaryMenuExpanded")
        for (name in listOf("PURIFICATION_ADVANCED", "ENHANCEMENT_ADVANCED", "APPEARANCE", "COMPATIBILITY")) {
            val branch = secondary.after("SettingsSearchSection.$name -> {").before("}")
            val update = branch.indexOf("settingsExpansionState.setSecondary(section.name, expanded)")
            assertTrue(update >= 0)
            assertTrue(update < branch.indexOf("if (!changed) return false"))
            assertTrue(update < branch.indexOf("?: return false"))
        }
        val category = SettingsUiSource.function("setAdvancedCategoryExpanded")
        val update = category.indexOf("settingsExpansionState.setCategory(category.name, expanded)")
        assertTrue(update >= 0 && update < category.indexOf("advancedCategorySections[category] ?: return"))
        assertTrue(update < category.indexOf("animateSecondarySection("))
    }
}
