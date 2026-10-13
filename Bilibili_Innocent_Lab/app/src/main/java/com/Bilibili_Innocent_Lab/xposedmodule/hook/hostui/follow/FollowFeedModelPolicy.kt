package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

/** 分组只使用同一模型对象的归属与首尾；不借用顶层扁平列表判断引用块边界。 */
internal object FollowFeedModelPolicy {
    val descriptionModules = setOf("Desc", "OpusDesc", "OpusDescList")

    fun reference(module: Any, identity: Long, localIdentity: Long, localModules: List<*>,
        quoted: Boolean, embeddedVideo: Boolean): FollowFeedReference? = when {
        quoted && localIdentity > 0 -> FollowFeedReference(localIdentity,
            localModules.firstOrNull() === module, localModules.lastOrNull() === module, quoted = true)
        !quoted && embeddedVideo -> FollowFeedReference(identity, first = true, last = true, quoted = false)
        else -> null
    }
}
