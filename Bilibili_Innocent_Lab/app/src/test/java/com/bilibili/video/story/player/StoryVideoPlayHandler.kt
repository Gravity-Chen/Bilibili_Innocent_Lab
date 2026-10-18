package com.bilibili.video.story.player

import com.bilibili.video.story.player.datasource.PlayableProvider
import tv.danmaku.biliplayerv2.service.CurrentVideoPointer
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.videoplayer.coreV2.MediaItem
import com.bilibili.lib.media.resource.MediaResource

class StoryVideoPlayHandler(@JvmField val source: PlayableProvider = PlayableProvider()) {
    var pointer: CurrentVideoPointer? = CurrentVideoPointer()
    var selectionResult = 0
    fun getCurrentVideoItem(): CurrentVideoPointer? = pointer
    fun select(index: Int): Int {
        if (pointer == null) return -1
        if (selectionResult != 0) return selectionResult
        source.currentIndex = index
        pointer?.index = index
        return 0
    }
}

class `StoryVideoPlayHandler$mPlayableConsumer$1` {
    fun consume(parameters: Video.PlayableParams, resource: MediaResource, previous: MediaItem?, position: Long?): MediaItem? = MediaItem()
}
