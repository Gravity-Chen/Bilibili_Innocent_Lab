package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.*
import org.junit.Test

class SettingsActionCatalogTest {
    @Test fun allRecommendationPanelOptionsCanNavigateBeforeThePageExists() {
        val descriptors = SettingsActionCatalog.descriptors().associateBy { it.storageKey }
        HomeRecommendFilterCatalog.preferenceKeys.forEach { key ->
            val descriptor = descriptors.getValue(key)
            assertEquals(SettingsCatalog.byStorageKey.getValue(key).id, descriptor.id)
            assertEquals(1, descriptor.page)
            assertFalse(descriptor.directToggle)
            assertFalse(descriptor.favoriteEligible)
        }
    }

    @Test fun navigationMetadataIsAvailableBeforeAnyPageOrControlExists() {
        val descriptors = SettingsActionCatalog.descriptors()
        assertTrue(descriptors.size > 40)
        assertEquals(descriptors.size, descriptors.map { it.id }.distinct().size)
        assertTrue(descriptors.all { !it.directToggle && !it.favoriteEligible })
        descriptors.forEach { descriptor ->
            val spec = SettingsCatalog.byId.getValue(descriptor.id)
            assertEquals(spec.storageKey, descriptor.storageKey)
            assertEquals(spec.labelRes, descriptor.titleRes)
            assertTrue(descriptor.page in 1..4)
        }
    }

    @Test fun complexNonBooleanControlsRemainNavigationInsteadOfBooleanWrites() {
        val descriptors = SettingsActionCatalog.descriptors().associateBy { it.storageKey }
        listOf(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            FeaturePreferences.COMMENT_FILTER_KEYWORDS, FeaturePreferences.HOST_BACKGROUND_PRESET).forEach { key ->
            assertFalse(descriptors.getValue(key).directToggle)
            assertFalse(descriptors.getValue(key).favoriteEligible)
        }
    }
}
