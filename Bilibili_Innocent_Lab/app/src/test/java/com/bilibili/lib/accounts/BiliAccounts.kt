package com.bilibili.lib.accounts

import android.content.Context

class BiliAccounts {
    var loggedIn = true
    fun isLogin(): Boolean {
        check(!fail) { "account fixture failure" }
        return loggedIn
    }
    companion object {
        val instance = BiliAccounts()
        var fail = false
        @JvmStatic fun get(context: Context?): BiliAccounts {
            check(!fail) { "account fixture failure" }
            return instance
        }
    }
}
