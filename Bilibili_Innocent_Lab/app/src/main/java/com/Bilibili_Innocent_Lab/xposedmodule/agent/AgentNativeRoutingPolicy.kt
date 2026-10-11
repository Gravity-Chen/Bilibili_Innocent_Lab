package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject

/** 观察不转交控制；界面动作必须在原生窗口监听确认释放后单向接管。 */
internal class AgentNativeRoutingPolicy {
    @Volatile var nativeAttempted = false
        private set
    @Volatile var nativeActive = false
        private set
    @Volatile var uiOnly = false
        private set
    private val capabilities = mutableMapOf<String, Boolean>()
    var accountRefusal: String? = null
        private set

    fun startingNative() { check(!uiOnly); nativeAttempted = true }
    fun began() { check(nativeAttempted && !uiOnly); nativeActive = true }
    fun noNative() { check(!nativeAttempted); uiOnly = true }
    fun capabilities(data: JSONObject) {
        AgentToolCatalog.nativeReads.forEach { operation -> (data.opt(operation) as? Boolean)?.let { capabilities[operation] = it } }
        accountRefusal = data.optString("account_read_state").takeIf { accountFailure(it) }
    }
    fun supported(operation: String): Boolean? = capabilities[operation]
    fun unavailable(operation: String) { capabilities[operation] = false }

    fun handoff(response: JSONObject): Boolean {
        if (!response.optBoolean("ok") || response.optJSONObject("data")?.optString("status") != "finished" ||
            response.optJSONObject("data")?.optBoolean("native_window_released") != true) return false
        nativeActive = false
        uiOnly = true
        return true
    }

    companion object {
        fun accountFailure(error: String): Boolean = error in setOf("account_changed", "account_read_not_authorized",
            "account_identity_unavailable", "expired", "not_logged_in", "unavailable")
        fun unsupported(error: String): Boolean = error in setOf("unsupported_operation", "use_ui_tools",
            "ranking_unavailable", "popular_unavailable", "ranking_regions_unavailable", "video_metrics_unavailable",
            "history_rpc_unavailable", "user_videos_rpc_unavailable", "following_feed_rpc_unavailable", "live_status_rpc_unavailable", "comments_rpc_unavailable")
    }
}
