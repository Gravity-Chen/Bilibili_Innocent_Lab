package com.bilibili.app.comm.restrict

enum class RestrictedType { LESSONS }
class RestrictedMode {
    companion object {
        var enabled = false
        @JvmStatic fun isEnable(type: RestrictedType, scene: String) = enabled
    }
}
