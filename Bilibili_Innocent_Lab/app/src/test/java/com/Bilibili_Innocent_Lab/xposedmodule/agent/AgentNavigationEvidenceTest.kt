package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentNavigationEvidenceTest {
    private fun state(id: String = "av1") = JSONObject().put("foreground", true).put("task_page", true)
        .put("page", "video").put("video_id", id).put("navigation_pending", false).put("rendered_content_verified", false)
    @Test fun incompleteWrongOrCoercedStatesCannotClearPendingNavigation() {
        val navigation = AgentNavigationEvidence().apply { requested("av1") }
        for (data in listOf(state("av2"), state().put("foreground", false), state().put("task_page", false),
            state().put("navigation_pending", true), state().put("page", "search"), state().put("foreground", "true"),
            state().apply { remove("navigation_pending") })) {
            assertFalse(navigation.observe(data)); assertEquals("av1", navigation.pending)
        }
        assertTrue(navigation.observe(state()))
        assertNull(navigation.pending)
    }
    @Test fun onlyVerifiedHostAliasesCanConfirmTheRequestedBvWithAnAvWindowIdentity() {
        val bv = "BV0000000001"
        val navigation = AgentNavigationEvidence().apply { requested(bv) }
        assertFalse(navigation.observe(state("av42")))
        navigation.recordAliases(JSONObject().put("source", "host_rpc").put("canonical_video_id", "av42")
            .put("video_aliases", JSONArray(listOf(bv, "av42"))))
        assertTrue(navigation.observe(state("av42")))
    }
    @Test fun navigationRequestCannotSatisfyOpeningAndOpeningDoesNotEstablishPlayback() {
        val open = AgentGoalEvidence("打开 av1")
        val requested = JSONObject().put("ok", true).put("data", JSONObject().put("navigation", "requested").put("observed", false))
        open.record("open_video", JSONObject().put("video_id", "av1"), requested)
        assertFalse(open.complete())
        open.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", state()))
        assertTrue(open.complete())
        val play = AgentGoalEvidence("播放 av1")
        play.record("open_video", JSONObject().put("video_id", "av1"), requested)
        play.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", state()))
        assertFalse(play.complete())
        assertTrue(play.limitation().contains("不能证明正在播放"))
    }
}
