package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingsExpansionStateTest {
    private val sections = setOf("PURIFICATION_ADVANCED", "ENHANCEMENT_ADVANCED", "APPEARANCE", "COMPATIBILITY")
    private val categories = (0 until 14).associate { "CATEGORY_$it" to false } + ("PURIFY_SHARED" to true)

    private fun newState(schema: Int = 1, saved: Map<String, Boolean> = emptyMap()) =
        SettingsExpansionState(sections, categories, setOf("PURIFY_SHARED"), schema, saved)

    @Test fun defaultsIncludeEveryDeclaredStateAndKeepTheFixedCategoryVisible() {
        val state = newState()
        assertEquals(19, state.snapshot().size)
        assertTrue(sections.none(state::secondary))
        assertTrue(state.category("PURIFY_SHARED"))
        state.setCategory("PURIFY_SHARED", false)
        assertTrue(state.category("PURIFY_SHARED"))
    }

    @Test fun anUnbuiltPageSurvivesMultipleSaveAndRecreateCycles() {
        val first = newState(saved = mapOf("secondary:ENHANCEMENT_ADVANCED" to true, "category:CATEGORY_7" to true))
        val second = newState(saved = first.snapshot())
        val third = newState(saved = second.snapshot())
        assertTrue(third.secondary("ENHANCEMENT_ADVANCED"))
        assertTrue(third.category("CATEGORY_7"))
        assertEquals(first.snapshot(), third.snapshot())
    }

    @Test fun newCommandsReplacePendingRestoreBeforeThePageExists() {
        val state = newState(saved = mapOf("secondary:APPEARANCE" to true, "category:CATEGORY_3" to true))
        state.setSecondary("APPEARANCE", false)
        state.setCategory("CATEGORY_3", false)
        val recreated = newState(saved = state.snapshot())
        assertFalse(recreated.secondary("APPEARANCE"))
        assertFalse(recreated.category("CATEGORY_3"))
        state.setCategory("CATEGORY_3", true)
        assertTrue(state.category("CATEGORY_3"))
    }

    @Test fun unknownNamesAreIgnoredAndUnknownSchemaUsesDeclaredDefaults() {
        val saved = mapOf("secondary:APPEARANCE" to true, "category:PURIFY_SHARED" to false,
            "secondary:REMOVED" to true, "category:FUTURE" to true)
        val compatible = newState(saved = saved)
        assertTrue(compatible.secondary("APPEARANCE"))
        assertTrue(compatible.category("PURIFY_SHARED"))
        assertFalse(compatible.snapshot().containsKey("secondary:REMOVED"))
        assertFalse(compatible.snapshot().containsKey("category:FUTURE"))
        assertEquals(newState().snapshot(), newState(schema = 2, saved = saved).snapshot())
    }

    @Test fun inputAndOutputSnapshotsDoNotAliasLiveState() {
        val input = mutableMapOf("secondary:COMPATIBILITY" to true)
        val state = newState(saved = input)
        input["secondary:COMPATIBILITY"] = false
        assertTrue(state.secondary("COMPATIBILITY"))
        val saved = state.snapshot()
        state.setSecondary("COMPATIBILITY", false)
        assertEquals(true, saved["secondary:COMPATIBILITY"])
    }

    @Test fun identicalNamesInDifferentDomainsRemainIndependent() {
        val state = SettingsExpansionState(setOf("SHARED"), mapOf("SHARED" to false))
        state.setSecondary("SHARED", true)
        assertTrue(state.secondary("SHARED"))
        assertFalse(state.category("SHARED"))
    }
}
