package com.bilibili.video.story.player.quality

import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.player.tangram.basic.StoryPreferenceFixture
import android.content.Context
import com.bilibili.lib.accountinfo.BiliAccountInfo
import com.bilibili.lib.accounts.BiliAccounts
import com.bilibili.video.story.player.StoryVideoPlayHandler
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import kotlin.coroutines.Continuation

class StoryQualityService(
    @JvmField val controller: StoryVideoPlayHandler = StoryVideoPlayHandler(),
    @JvmField val context: Context? = null,
    @JvmField val account: BiliAccountInfo = BiliAccountInfo(),
    @JvmField val core: IPlayerCoreService = FixtureCore(),
    @JvmField val accounts: BiliAccounts = BiliAccounts.instance
) {
    var selected: StoryPreferenceFixture = StoryPreferenceFixture.Auto
    fun manual(preference: StoryPreferenceFixture, continuation: Continuation<*>): Any {
        selected = preference
        return Unit
    }
    private fun matchPreference(continuation: Continuation<*>): Any = Unit
}

class FixtureCore(var resource: MediaResource? = MediaResource()) : IPlayerCoreService {
    var playState = 4
    var position = 321
    override fun getMediaResource(): MediaResource? = resource
    override fun getState() = playState
    override fun getRealCurrentPosition() = position
}

object StoryQualityFacade {
    @JvmStatic fun integerDefault(): Int = 32
    @JvmStatic fun policy(): StoryPreferenceFixture = StoryPreferenceFixture.Auto
    @JvmStatic fun resource(value: MediaResource?) = Unit
}

object OtherStoryQualityFacade {
    @JvmStatic fun policy(): StoryPreferenceFixture = StoryPreferenceFixture.Auto
    @JvmStatic fun resource(value: MediaResource?) = Unit
    @JvmStatic fun wrongInteger(): String = "32"
}
