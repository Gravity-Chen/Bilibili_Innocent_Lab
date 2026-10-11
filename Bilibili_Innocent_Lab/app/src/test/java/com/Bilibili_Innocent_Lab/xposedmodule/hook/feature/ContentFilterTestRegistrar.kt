package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator

/** 让适配后的无参列表 getter 也经过真实 Modern 回调链。 */
internal class ContentFilterTestRegistrar(
    private val loader: ClassLoader,
    private val recorder: PlayerPortTestRegistrar = PlayerPortTestRegistrar()
) : HookRegistrar by recorder {
    override fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        exceptionPolicy: HookExceptionPolicy,
        block: ModernMemberHookCreator.() -> Unit
    ) {
        require(point.paramClassNames.isNullOrEmpty())
        recorder.exact(id, loader.loadClass(point.className), point.methodName, block = block)
    }

    fun items(id: String, receiver: Any, source: List<*>): List<*> =
        recorder.invoke(id, receiver, original = { source }) as List<*>
}
