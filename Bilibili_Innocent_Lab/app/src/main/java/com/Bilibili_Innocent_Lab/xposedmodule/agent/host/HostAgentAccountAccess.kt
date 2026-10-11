package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BiliAccessKeyProbe
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BiliAccessKeyState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/** 只保存成员描述符。每次重读宿主当前账号，令牌原文仅由共用 Probe 在宿主内判空。 */
internal class HostAgentAccountAccess private constructor(
    private val get: Method,
    private val mid: Method,
    private val probe: BiliAccessKeyProbe
) {
    data class State(val code: String, val mid: Long?)

    fun state(context: Context): State {
        val tokenState = probe.state(context)
        if (tokenState != BiliAccessKeyState.READY) return State(tokenState.code, null)
        val identity = runCatching { (mid.invoke(get.invoke(null, context)) as? Number)?.toLong()?.takeIf { it > 0 } }.getOrNull()
        return State(if (identity == null) "account_identity_unavailable" else "ready", identity)
    }

    companion object {
        fun resolve(loader: ClassLoader): HostAgentAccountAccess? = runCatching {
            val accounts = KavaMemberLookup.classOrNull(loader, BiliAccessKeyProbe.ACCOUNTS_CLASS) ?: return null
            val get = KavaMemberLookup.methodOrNull(accounts, "get", classOf<Context>())
                ?.takeIf { it.isStatic && it.returnType == accounts } ?: return null
            val mid = hostGetter(accounts, "mid", classOf<Long>()) ?: return null
            val token = hostGetter(accounts, "getAccessToken") ?: return null
            val probe = BiliAccessKeyProbe.resolve(loader, tokenClass = token.returnType.name) ?: return null
            HostAgentAccountAccess(get, mid, probe)
        }.getOrNull()
    }
}
