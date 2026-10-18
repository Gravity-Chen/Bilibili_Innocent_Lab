package com.bilibili.teenagersmode

class TeenagersMode {
    var enabled = false
    fun isEnable(scene: String) = enabled
    companion object {
        private val singleton = TeenagersMode()
        @JvmStatic fun getInstance() = singleton
    }
}
