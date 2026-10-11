package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.*
import org.junit.Test

class PlayerBufferWiringContractTest {
    private val source = SourceContract.read("hook/feature/PlayerBufferFeatureInstaller.kt")
    @Test fun `buffer entry is registered inside authorized feature installation`() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("PlayerBufferFeatureInstaller("))
        listOf("MODE", "SECONDS", "MEMORY_MB", "DISK_MB", "WIFI_ONLY").forEach {
            assertTrue(hook.contains("FeaturePreferences.PLAYER_BUFFER_$it"))
        }
    }
    @Test fun `native dictionaries are copied and source scopes are restored even on host failure`() {
        assertTrue(source.contains("val copy = Bundle(source)"))
        assertTrue(source.contains("args[1] = copy"))
        assertTrue(source.contains("frame.previous == null"))
        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("sessions.commit(ticket, actual)"))
        assertTrue(source.contains("sessions.current(entry)"))
        assertTrue(source.contains("!point.normalHandler.isInstance(instance)"))
    }
    @Test fun `buffering uses native lifecycle without a polling loop or replacement URL`() {
        assertFalse(source.contains("setBufferControl("))
        assertFalse(source.contains("setOptionLong("))
        assertFalse(source.contains("postDelayed("))
        assertFalse(source.contains("scheduleAtFixedRate("))
        assertFalse(source.contains("putString(\"url\""))
        assertTrue(source.contains("ThreadPoolExecutor(1, 1"))
        assertTrue(source.contains("HostThreadGuard.run("))
    }
    @Test fun `host network permission is checked before the scoped lint exception`() {
        val network = SourceContract.read("hook/feature/PlayerBufferNetworkState.kt")
        assertTrue(network.contains("context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)"))
        assertTrue(network.indexOf("context.checkSelfPermission") < network.indexOf("manager.registerDefaultNetworkCallback"))
        assertTrue(network.contains("PackageManager.PERMISSION_GRANTED"))
    }
}
