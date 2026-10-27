package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsNavigationCatalogTest {
    @Test fun allSevenRuntimeControllersAreNavigationOnlyAndNeverGainBooleanAuthority() {
        val descriptors = RuntimeSettingsNavigationCatalog.descriptors()
        assertEquals(7, descriptors.size)
        assertEquals(7, descriptors.map { it.id }.distinct().size)
        assertTrue(descriptors.all { !it.favoriteEligible && !it.directToggle && it.page == 4 })
        assertTrue(SettingStore(descriptors, emptyMap()).snapshot().isEmpty())
        assertEquals(setOf("APPEARANCE", "COMPATIBILITY"), descriptors.map { it.section }.toSet())
    }
}
