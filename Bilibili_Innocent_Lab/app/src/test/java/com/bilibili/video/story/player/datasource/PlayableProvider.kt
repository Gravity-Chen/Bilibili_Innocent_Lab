package com.bilibili.video.story.player.datasource

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.biliplayerv2.service.resolve.AbsMediaResourceResolveTask
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.video.story.player.`StoryVideoPlayHandler$mPlayableConsumer$1`

interface FixtureResolveListener {
    fun success(resource: MediaResource)
    fun onError()
}
class PlayableProvider {
    data class ReplayCall(val qn: Int?, val position: Long?, val language: String?, val reload: Boolean, val productionType: Int)
    val values = arrayListOf<Video.PlayableParams>()
    @JvmField val consumer = `StoryVideoPlayHandler$mPlayableConsumer$1`()
    var currentIndex = 0
    var reloads = 0
    val reloadCalls = arrayListOf<ReplayCall>()
    var onReload: ((ReplayCall) -> Unit)? = null
    fun at(index: Int, scene: String?): Video.PlayableParams? = values.getOrNull(index)
    fun request(qn: Int?, preview: Boolean, scene: String?, mode: Int, extra: Boolean,
                parameters: Video.PlayableParams, listener: FixtureResolveListener?) = Unit
    fun reload(qn: Int?, position: Long?, scene: String?, preview: Boolean, mode: Int,
               callback: ((Any?) -> Unit)?) {
        reloads++
        val call = ReplayCall(qn, position, scene, preview, mode)
        reloadCalls += call
        onReload?.invoke(call)
    }

    class resolve {
        class `1`(val task: AbsMediaResourceResolveTask, flash: String?, val parameters: Video.PlayableParams,
                  listener: FixtureResolveListener?, val provider: PlayableProvider, continuation: Continuation<*>?) : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
            fun create(value: Any?, completion: Continuation<*>): Continuation<Any?> =
                `1`(task, null, parameters, null, provider, completion)
            fun invokeSuspend(value: Any?): Any? = value
        }
    }
}
