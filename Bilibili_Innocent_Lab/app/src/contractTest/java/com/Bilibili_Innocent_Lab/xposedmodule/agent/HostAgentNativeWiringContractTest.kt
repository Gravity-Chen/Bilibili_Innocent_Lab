package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class HostAgentNativeWiringContractTest {
    @Test fun bothHostInitializationPathsCarryExplicitAccountAuthorization() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("config.values[FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED] == true"))
        assertTrue(hook.contains("activeHookConfig.get()?.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false) == true"))
    }
    @Test fun personalCallsCheckAccountBeforeAndAfterAndNeverExposeCredentials() {
        val host = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val page = host.after("private fun readPage(").before("private fun stamp(")
        assertTrue(page.indexOf("if (personal) requireAccount(lease)") < page.indexOf("val page = when"))
        assertTrue(page.after("val page = when").contains("if (personal) requireAccount(lease)"))
        assertFalse(host.contains("getAccessKey"))
        assertFalse(host.contains("getAccessToken"))
        val access = SourceContract.read("agent/host/HostAgentAccountAccess.kt")
        assertTrue(access.contains("probe.state(context)"))
        assertFalse(access.contains("getAccessKey"))
        val delivery = host.after("private fun deliver(").before("private data class Binding(")
        assertTrue(delivery.contains("if (request.operation in PERSONAL_READS) requireAccount(lease)"))
        assertTrue(delivery.before("request.callback.transact").contains("accountRefusal(lease)"))
    }
    @Test fun closeAcknowledgementFollowsWindowReleaseAndMetricsBypassDetailCache() {
        val host = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val closing = host.after("} else if (closing) {").before("} else {\n                            try")
        assertTrue(closing.indexOf("release(lease)") < closing.indexOf("native_window_released"))
        assertTrue(closing.contains("windows.canHandoff(lease)"))
        assertTrue(closing.contains("handoff && released"))
        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val callback = window.after("private inner class TaskCallback").before("// 此 View 注入宿主窗口")
        assertTrue(callback.contains("ownershipGeneration == lease.generation"))
        assertFalse(callback.contains("session.isTaskActive"))
        val metrics = host.after("\"get_video_metrics\" -> {").before("\"get_video_details\" -> {")
        assertTrue(metrics.contains("detail.query(id, timeout(lease))"))
        assertFalse(metrics.contains("detailCache"))
    }
}
