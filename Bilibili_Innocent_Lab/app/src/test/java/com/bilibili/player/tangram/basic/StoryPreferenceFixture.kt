package com.bilibili.player.tangram.basic

abstract class StoryPreferenceFixture {
    object Auto : StoryPreferenceFixture()
    class Fixed(val qn: Int) : StoryPreferenceFixture()
}
