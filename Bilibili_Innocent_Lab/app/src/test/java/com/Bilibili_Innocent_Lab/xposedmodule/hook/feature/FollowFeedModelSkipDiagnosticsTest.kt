package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class FollowFeedModelSkipDiagnosticsTest {
    @Test fun repeatedBudgetAndParentSkipsHaveBoundedFixedDiagnosticsWithoutAppliedEvidence() {
        val loader = javaClass.classLoader!!
        val stages = ArrayList<FeatureRuntimeStage>()
        val logs = ArrayList<Pair<String, String>>()
        val statuses = ArrayList<Pair<String, String>>()
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), PlayerPortTestRegistrar(),
            { key, message -> logs += key to message }, { _, _ -> }, { key, value -> statuses += key to value },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        repeat(100) {
            FollowFeedFeatureInstaller.reportModelSkip(environment, reported, "local-module-budget")
            FollowFeedFeatureInstaller.reportModelSkip(environment, reported, "parent-chain-unavailable")
        }
        assertEquals(2, logs.size); assertEquals(2, statuses.size)
        assertTrue(statuses.all { it.first == "follow_feed_model" })
        assertTrue(stages.isEmpty())
    }
}
