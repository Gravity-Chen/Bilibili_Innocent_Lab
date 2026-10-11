package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class HostBackgroundSettingsStoreTest {
    private val previous = mapOf<String, Any>(
        FeaturePreferences.HOST_BACKGROUND_PRESET to "custom",
        FeaturePreferences.HOST_BACKGROUND_ASSET to "11111111-1111-1111-1111-111111111111",
        FeaturePreferences.HOST_BACKGROUND_BLUR to 12,
        FeaturePreferences.HOST_BACKGROUND_SATURATION to 80,
        FeaturePreferences.HOST_BACKGROUND_VEIL to 30,
        "unrelated" to "keep"
    )
    private val draft = HostBackgroundConfig(HostBackgroundPreset.CUSTOM, 22, 110, 45,
        "22222222-2222-2222-2222-222222222222")

    @Test fun failedDiskCommitRestoresTheActualPreviousAssetAndEveryBackgroundSetting() {
        val preferences = MemoryPreferences(previous, listOf(false, true))
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(previous, preferences.values)
        assertEquals(2, preferences.commits)
    }

    @Test fun failedRollbackDiskWriteStillRestoresMemoryAndNeverReportsSuccess() {
        val preferences = MemoryPreferences(previous, listOf(false, false))
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(previous, preferences.values)
        assertEquals(2, preferences.commits)
    }

    @Test fun exceptionAfterMemoryMutationRestoresMissingKeysAsMissingAndPreservesOtherSettings() {
        val original = mapOf<String, Any>(FeaturePreferences.HOST_BACKGROUND_PRESET to "off", "unrelated" to 7)
        val preferences = MemoryPreferences(original, throwAfterFirstWrite = true)
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(original, preferences.values)
    }

    @Test fun successfulSaveNormalizesValuesAndPreservesUnrelatedSettingsWithoutRollback() {
        val preferences = MemoryPreferences(previous)
        assertTrue(HostBackgroundSettingsStore.save(preferences.instance,
            draft.copy(blur = 99, saturation = -1, veil = 100, asset = "../invalid")))
        assertEquals("custom", preferences.values[FeaturePreferences.HOST_BACKGROUND_PRESET])
        assertEquals(60, preferences.values[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(0, preferences.values[FeaturePreferences.HOST_BACKGROUND_SATURATION])
        assertEquals(90, preferences.values[FeaturePreferences.HOST_BACKGROUND_VEIL])
        assertEquals("", preferences.values[FeaturePreferences.HOST_BACKGROUND_ASSET])
        assertEquals("keep", preferences.values["unrelated"])
        assertEquals(1, preferences.commits)
    }

    /** 与平台一致：先改内存，再返回磁盘失败或抛错，不能用“失败不改内存”的假实现。 */
    private class MemoryPreferences(original: Map<String, Any>,
        private val results: List<Boolean> = listOf(true), private val throwAfterFirstWrite: Boolean = false) {
        val values = original.toMutableMap()
        var commits = 0
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { pending[args!![0] as String] = args[1]; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "commit" -> {
                        for ((key, value) in pending) if (value == null) values.remove(key) else values[key] = value
                        commits++
                        if (throwAfterFirstWrite && commits == 1) error("Injected failure after memory write")
                        results.getOrElse(commits - 1) { true }
                    }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
