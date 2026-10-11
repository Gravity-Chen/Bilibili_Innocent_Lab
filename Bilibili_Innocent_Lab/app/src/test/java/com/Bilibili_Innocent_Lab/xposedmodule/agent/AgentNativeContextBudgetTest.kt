package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentNativeContextBudgetTest {
    @Test fun projectionRetainsNativeDatasetRankUnknownCountersLiveCoverageAndCommentBodies() {
        val data = JSONObject().put("source", "host_rpc").put("dataset", "ranking").put("coverage", "returned_page")
            .put("statistics", JSONObject().put("status", "reported").put("view_count", 0).put("like_count", JSONObject.NULL))
            .put("videos", JSONArray().put(JSONObject().put("video_id", "av1").put("ranking_position", 5).put("position_basis", "server_rank_list_order")))
            .put("comments", JSONArray().put(JSONObject().put("comment_id", "11").put("message", "真实公开正文")))
            .put("live_status", "unknown").put("scope", "current_account_feed_up_list")
        val result = AgentContextBudget.project(JSONObject().put("ok", true).put("data", data)).getJSONObject("data")
        assertEquals("ranking", result.getString("dataset"))
        assertEquals(5, result.getJSONArray("videos").getJSONObject(0).getInt("ranking_position"))
        assertEquals(0, result.getJSONObject("statistics").getInt("view_count"))
        assertTrue(result.getJSONObject("statistics").isNull("like_count"))
        assertEquals("unknown", result.getString("live_status"))
        assertEquals("current_account_feed_up_list", result.getString("scope"))
        assertEquals("真实公开正文", result.getJSONArray("comments").getJSONObject(0).getString("message"))
        assertFalse(result.has("access_key"))
    }
}
