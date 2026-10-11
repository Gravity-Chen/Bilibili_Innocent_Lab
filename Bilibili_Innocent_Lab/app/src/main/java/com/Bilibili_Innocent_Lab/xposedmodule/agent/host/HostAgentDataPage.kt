package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.json.JSONObject

/** 已投影的业务事实；只保留有界 JSON 与可加入本任务观察集合的身份，不持有宿主响应。 */
internal data class HostAgentDataPage(
    val data: JSONObject,
    val videos: List<HostAgentVideo> = emptyList(),
    val next: String? = null
)
