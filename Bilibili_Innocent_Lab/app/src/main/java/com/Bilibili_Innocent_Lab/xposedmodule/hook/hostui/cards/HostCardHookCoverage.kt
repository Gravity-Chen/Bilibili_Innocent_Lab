package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

/** 按实际可辨认的宿主家族计量；缺失和注册失败留在分母，旧类缺席不强造期望。 */
internal data class HostCardHookCoverage(val hooks: Int, val expected: Int, val ready: Int,
    val states: Map<String, String> = emptyMap()) {
    val complete: Boolean get() = ready == expected
    val label: String get() = if (expected == 0) "not-applicable" else "$ready/$expected"
    companion object { val NONE = HostCardHookCoverage(0, 0, 0) }
}
