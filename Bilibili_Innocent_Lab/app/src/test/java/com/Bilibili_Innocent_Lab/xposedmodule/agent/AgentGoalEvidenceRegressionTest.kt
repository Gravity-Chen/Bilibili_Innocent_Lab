package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 业务完成必须覆盖用户指定的身份和范围，不能由单个成功 RPC 或部分正文代替。 */
class AgentGoalEvidenceRegressionTest {
    private var sequence = 0

    private fun record(evidence: AgentGoalEvidence, operation: String, arguments: JSONObject, data: JSONObject) {
        val call = AgentModelToolCall("regression_${++sequence}", operation, arguments)
        evidence.record(call.name, call.arguments, JSONObject().put("ok", true).put("data", data))
    }

    private fun video(id: String) = JSONObject().put("video_id", id)
    private fun videos(vararg ids: String) = JSONArray(ids.map(::video))
    private fun args(key: String, value: String) = JSONObject().put(key, value)
    private fun metrics(id: String, count: Any = 42L) = JSONObject().put("source", "host_rpc").put("video_id", id)
        .put("statistics", JSONObject().put("status", "reported").put("view_count", count))
    private fun comments(id: String, message: Any = "这是一条真实返回的公开评论") = JSONObject()
        .put("source", "host_rpc").put("dataset", "comments").put("video_id", id)
        .put("comments", JSONArray().put(JSONObject().put("comment_id", "1").put("video_id", id).put("message", message)))
    private fun live(user: String, status: String) = JSONObject().put("source", "host_rpc").put("dataset", "up_live_status")
        .put("user_id", user).put("live_status", status)
    private fun archive(user: String, id: String) = JSONObject().put("source", "host_rpc").put("dataset", "user_videos")
        .put("requested_user_id", user).put("items", videos(id))
    private fun ranking(position: Int, id: String) = JSONObject().put("source", "host_rpc").put("dataset", "ranking")
        .put("region_id", "0").put("videos", JSONArray().put(video(id).put("ranking_position", position)))

    @Test fun wrongCandidateCommentsCannotCompleteTheRequestedVideo() {
        val evidence = AgentGoalEvidence("查看 av1 的评论内容")
        record(evidence, "get_video_comments", args("video_id", "av2"), comments("av2"))
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("尚未"))
        record(evidence, "get_video_comments", args("video_id", "av1"), comments("av1"))
        assertTrue(evidence.complete())
    }

    @Test fun verifiedBvCanonicalAvCanSatisfyTheSameVideosMetrics() {
        val bv = "BV0000000001"
        val evidence = AgentGoalEvidence("查看 $bv 的播放量")
        record(evidence, "get_video_details", args("video_id", bv), JSONObject().put("source", "host_rpc")
            .put("video_id", bv).put("canonical_video_id", "av42").put("video_aliases", JSONArray(listOf(bv, "av42"))))
        assertEquals("av42", evidence.preferredVideo())
        assertFalse(evidence.complete())
        record(evidence, "get_video_metrics", args("video_id", "av42"), metrics("av42"))
        assertTrue(evidence.complete())
    }

    @Test fun anUnverifiedAvCannotStandInForAnExplicitBvGoal() {
        val evidence = AgentGoalEvidence("查看 BV0000000001 的播放量")
        record(evidence, "get_video_metrics", args("video_id", "av42"), metrics("av42"))
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    @Test fun chineseOneHundredAndOneRequiresPosition101RatherThan100() {
        val evidence = AgentGoalEvidence("查看热榜第一百零一名")
        assertEquals(101, evidence.desiredRank)
        record(evidence, "get_ranking", JSONObject(), ranking(100, "av100"))
        assertFalse(evidence.complete())
        record(evidence, "get_ranking", JSONObject(), ranking(101, "av101"))
        assertTrue(evidence.complete())
        assertEquals("av101", evidence.preferredVideo())
    }

    @Test fun chineseTwoHundredAndOneCannotBeTruncatedToTheLastAllowedPosition() {
        val evidence = AgentGoalEvidence("查看热榜第二百零一名")
        record(evidence, "get_ranking", JSONObject(), ranking(200, "av200"))
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("200"))
    }

    @Test fun multiUserLiveGoalRequiresEachRequestedUserAndRetainsUnknownAsUnknown() {
        val evidence = AgentGoalEvidence("查看 UID 1 和 UID 2 是否正在直播")
        assertEquals(listOf("1", "2"), evidence.seedUsers)
        record(evidence, "get_live_status", args("user_id", "1"), live("1", "live"))
        assertFalse(evidence.complete())
        record(evidence, "get_live_status", args("user_id", "2"), live("2", "unknown"))
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("未知"))
        record(evidence, "get_live_status", args("user_id", "2"), live("2", "offline"))
        assertTrue(evidence.complete())
    }

    @Test fun multiUserArchivesRequireBothUsersRatherThanOneSuccessfulPage() {
        val evidence = AgentGoalEvidence("查看 UID 1 与 UID 2 的投稿")
        record(evidence, "get_user_videos", args("user_id", "1"), archive("1", "av11"))
        assertFalse(evidence.complete())
        record(evidence, "get_user_videos", args("user_id", "2"), archive("2", "av22"))
        assertTrue(evidence.complete())
    }

    @Test fun multiVideoMetricsRequireEveryExplicitVideoIdentity() {
        val evidence = AgentGoalEvidence("查看 av1 和 av2 的播放量")
        record(evidence, "get_video_metrics", args("video_id", "av1"), metrics("av1"))
        assertFalse(evidence.complete())
        record(evidence, "get_video_metrics", args("video_id", "av2"), metrics("av2"))
        assertTrue(evidence.complete())
    }

    @Test fun multiVideoCommentGoalRequiresActualTextForEachExplicitVideo() {
        val evidence = AgentGoalEvidence("查看 av1 与 av2 的评论内容")
        record(evidence, "get_video_comments", args("video_id", "av1"), comments("av1"))
        assertFalse(evidence.complete())
        record(evidence, "get_video_comments", args("video_id", "av2"), comments("av2", JSONObject.NULL))
        assertFalse(evidence.complete())
        record(evidence, "get_video_comments", args("video_id", "av2"), comments("av2"))
        assertTrue(evidence.complete())
    }

    @Test fun emptyCommentAndHistoryPagesRemainLimitedRatherThanInventingContent() {
        val comments = AgentGoalEvidence("查看 av1 的评论内容")
        record(comments, "get_video_comments", args("video_id", "av1"), JSONObject().put("source", "host_rpc")
            .put("dataset", "comments").put("video_id", "av1").put("comments", JSONArray()))
        assertFalse(comments.complete())
        assertTrue(comments.canReportLimitation())
        assertTrue(comments.limitation().contains("真实评论正文"))
        val history = AgentGoalEvidence("查看最近看过的历史记录")
        record(history, "get_history", JSONObject(), JSONObject().put("source", "host_rpc")
            .put("dataset", "history").put("items", JSONArray()))
        assertFalse(history.complete())
        assertTrue(history.canReportLimitation())
        assertTrue(history.limitation().contains("只覆盖"))
    }

    @Test fun aMissingRequestedStatisticIsNotAReportedZero() {
        val evidence = AgentGoalEvidence("查看 av1 的播放量")
        record(evidence, "get_video_metrics", args("video_id", "av1"), metrics("av1", JSONObject.NULL))
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("不能把未知数值写成0"))
        record(evidence, "get_video_metrics", args("video_id", "av1"), metrics("av1", 0L))
        assertTrue(evidence.complete())
    }
}
