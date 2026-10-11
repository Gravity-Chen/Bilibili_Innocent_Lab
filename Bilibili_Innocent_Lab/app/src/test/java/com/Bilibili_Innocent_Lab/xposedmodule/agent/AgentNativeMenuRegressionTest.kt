package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 菜单按真实业务对象保存读状态；只有确认释放原生监听的 finish 才移交控制权。 */
class AgentNativeMenuRegressionTest {
    private var sequence = 0
    private fun call(operation: String, arguments: JSONObject = JSONObject()) =
        AgentModelToolCall("regression_${++sequence}", operation, arguments)
    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data)
    private fun args(key: String, value: String, cursor: String? = null) = JSONObject().put(key, value).apply {
        cursor?.let { put("cursor", it) }
    }
    private fun video(id: String) = JSONObject().put("video_id", id).put("title", "公开视频 $id")
    private fun archive(user: String, id: String, cursor: String? = null) = JSONObject().put("source", "host_rpc")
        .put("dataset", "user_videos").put("requested_user_id", user).put("items", JSONArray().put(video(id)))
        .put("next_cursor", cursor ?: JSONObject.NULL)
    private fun comments(id: String, cursor: String? = null) = JSONObject().put("source", "host_rpc")
        .put("dataset", "comments").put("video_id", id).put("comments", JSONArray().put(JSONObject()
            .put("comment_id", "1").put("video_id", id).put("message", "公开评论正文")))
        .put("next_cursor", cursor ?: JSONObject.NULL)
    private fun choices(actions: AgentDecisionActions, operation: String): List<JSONObject> =
        actions.menu(false).values.filter { it.first == operation }.map { it.second }
    private fun initialRouting() = AgentNativeRoutingPolicy().apply { startingNative(); began() }

    @Test fun oneUnsupportedNativeOperationDoesNotRemoveAnotherAvailableNativeMenu() {
        val actions = AgentDecisionActions("查看热榜第一的视频播放量")
        actions.record(call("get_capabilities"), ok(JSONObject().put("get_ranking", false)
            .put("get_video_metrics", true).put("get_host_state", true)))
        actions.record(call("get_host_state"), ok(JSONObject().put("foreground", true).put("interaction_guard", true)
            .put("video_id", "av1")))
        actions.record(call("get_ranking"), JSONObject().put("ok", false).put("error", "use_ui_tools")
            .put("data", JSONObject().put("required_operation", "get_ranking")))
        assertEquals("get_host_state", actions.menu(false).getValue("state").first)
        assertTrue(choices(actions, "get_video_metrics").any { it.optString("video_id") == "av1" })
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
    }

    @Test fun observingUiDuringAnActiveNativeSessionDoesNotTransferTheMenuToUiOnly() {
        val actions = AgentDecisionActions("查看热榜第一")
        actions.record(call("get_capabilities"), ok(JSONObject().put("get_ranking", true)))
        actions.record(call("get_ui_state"), ok(JSONObject().put("backend", "accessibility")
            .put("native_session_active", true).put("snapshot_id", "12345678-1234-1234-1234-123456789abc")
            .put("nodes", JSONArray())))
        assertEquals("get_host_state", actions.menu(false).getValue("state").first)
        assertTrue(choices(actions, "get_ranking").isNotEmpty())
    }

    @Test fun readingOneUsersArchiveRetainsTheSecondUsersFirstRead() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 的投稿")
        actions.record(call("get_user_videos", args("user_id", "1")), ok(archive("1", "av11")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_user_videos").any { it.optString("user_id") == "2" && !it.has("cursor") })
    }

    @Test fun eachUsersContinuationRetainsItsOwnUserIdentityAndCursor() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 的投稿")
        actions.record(call("get_user_videos", args("user_id", "1")), ok(archive("1", "av11", "page:1")))
        actions.record(call("get_user_videos", args("user_id", "2")), ok(archive("2", "av22", "page:2")))
        val pages = choices(actions, "get_user_videos").filter { it.has("cursor") }
        assertEquals(setOf("1" to "page:1", "2" to "page:2"), pages.map {
            it.getString("user_id") to it.getString("cursor")
        }.toSet())
        actions.record(call("get_user_videos", args("user_id", "1", "page:1")), ok(archive("1", "av12")))
        assertEquals(listOf("2" to "page:2"), choices(actions, "get_user_videos").filter { it.has("cursor") }.map {
            it.getString("user_id") to it.getString("cursor")
        })
    }

    @Test fun readingOneUsersLiveStatusRetainsTheSecondUsersStatusMenu() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 是否正在直播")
        actions.record(call("get_live_status", args("user_id", "1")), ok(JSONObject().put("source", "host_rpc")
            .put("dataset", "up_live_status").put("user_id", "1").put("live_status", "offline")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_live_status").any { it.optString("user_id") == "2" })
    }

    @Test fun readingOneVideosMetricsRetainsTheSecondExplicitVideosMenu() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的播放量")
        actions.record(call("get_video_metrics", args("video_id", "av1")), ok(JSONObject().put("source", "host_rpc")
            .put("video_id", "av1").put("statistics", JSONObject().put("status", "reported").put("view_count", 42L))))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_video_metrics").any { it.optString("video_id") == "av2" })
    }

    @Test fun readingOneVideosCommentsRetainsTheSecondExplicitVideosFirstRead() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的评论内容")
        actions.record(call("get_video_comments", args("video_id", "av1")), ok(comments("av1")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_video_comments").any { it.optString("video_id") == "av2" && !it.has("cursor") })
    }

    @Test fun differentVideoCommentPagesRetainIndependentContinuations() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的评论内容")
        actions.record(call("get_video_comments", args("video_id", "av1")), ok(comments("av1", "page:3")))
        actions.record(call("get_video_comments", args("video_id", "av2")), ok(comments("av2", "page:4")))
        assertEquals(setOf("av1" to "page:3", "av2" to "page:4"), choices(actions, "get_video_comments")
            .filter { it.has("cursor") }.map { it.getString("video_id") to it.getString("cursor") }.toSet())
    }

    @Test fun aDifferentRegionReadCannotOverwriteTheSelectedRegionsContinuation() {
        val actions = AgentDecisionActions("查看游戏区热榜")
        actions.record(call("get_regions"), ok(JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions")
            .put("regions", JSONArray().put(JSONObject().put("name", "游戏").put("region_id", "17").put("region_type", 2))
                .put(JSONObject().put("name", "音乐").put("region_id", "3").put("region_type", 2)))))
        for ((region, cursor) in listOf("17" to "page:5", "3" to "page:6")) {
            actions.record(call("get_ranking", args("region_id", region)), ok(JSONObject().put("source", "host_rpc")
                .put("dataset", "ranking").put("region_id", region).put("videos", JSONArray().put(video("av$region")
                    .put("ranking_position", 1))).put("next_cursor", cursor)))
        }
        assertEquals(listOf("17" to "page:5"), choices(actions, "get_ranking").filter { it.has("cursor") }.map {
            it.getString("region_id") to it.getString("cursor")
        })
    }

    @Test fun cancelledAcknowledgementCannotTransferEvenWhenItClaimsTheWindowWasReleased() {
        val routing = initialRouting()
        assertFalse(routing.handoff(ok(JSONObject().put("status", "cancelled").put("native_window_released", true))))
        assertTrue(routing.nativeActive)
        assertFalse(routing.uiOnly)
    }

    @Test fun finishedAcknowledgementStillRequiresAnExplicitWindowRelease() {
        val routing = initialRouting()
        assertFalse(routing.handoff(ok(JSONObject().put("status", "finished"))))
        assertFalse(routing.handoff(ok(JSONObject().put("status", "finished").put("native_window_released", false))))
        assertTrue(routing.nativeActive)
        assertFalse(routing.uiOnly)
    }

    @Test fun confirmedFinishedReleasePerformsAOneWayTransfer() {
        val routing = initialRouting()
        assertTrue(routing.handoff(ok(JSONObject().put("status", "finished").put("native_window_released", true))))
        assertFalse(routing.nativeActive)
        assertTrue(routing.uiOnly)
        assertThrows(IllegalStateException::class.java) { routing.startingNative() }
    }
}
