package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject

/** 只确认对应详情页已经进入前台，不把导航派发或页面身份当作播放状态。 */
internal class AgentNavigationEvidence {
    private val canonical = mutableMapOf<String, String>()
    var pending: String? = null
        private set

    fun recordAliases(data: JSONObject) {
        if (data.optString("source") != "host_rpc") return
        val id = data.optString("canonical_video_id").takeIf { it.matches(Regex("av[1-9][0-9]{0,18}")) } ?: return
        val values = data.optJSONArray("video_aliases") ?: return
        for (i in 0 until minOf(values.length(), 8)) (values.opt(i) as? String)?.let { canonical[it] = id }
        canonical[id] = id
        while (canonical.size > 256) canonical.remove(canonical.keys.first())
    }
    fun requested(id: String) { pending = id }
    fun observe(data: JSONObject): Boolean {
        if (!confirmed(data, pending) { canonical[it] ?: it }) return false
        pending = null
        return true
    }

    companion object {
        fun confirmed(data: JSONObject, expected: String?, canonical: (String) -> String = { it }): Boolean = expected != null &&
            data.opt("foreground") == true && data.opt("task_page") == true && data.optString("page") == "video" &&
            data.opt("navigation_pending") == false && canonical(data.optString("video_id")) == canonical(expected)
    }
}
