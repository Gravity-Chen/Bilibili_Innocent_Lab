package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * 安装期探针在真实宿主类上实测出的六个 wire 字段号。
 * 不做任何硬编码猜测：互异且都在 1..2^29-1，否则整体无效（Rust/JVM 两侧同样校验）。
 */
internal data class DanmakuWireFields(
    val elemsField: Int,
    val weightField: Int,
    val colorfulField: Int,
    val contentField: Int,
    val colorfulSrcField: Int,
    val colorfulTypeField: Int,
) {
    fun isValid(): Boolean {
        val all = intArrayOf(
            elemsField, weightField, colorfulField, contentField, colorfulSrcField, colorfulTypeField
        )
        for (value in all) {
            if (value < 1 || value > 536_870_911) return false
        }
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                if (all[i] == all[j]) return false
            }
        }
        return true
    }
}
