package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import org.junit.Assert.*
import org.junit.Test

class StoryQualityResourceAccessTest {
    enum class DrmType { No, WideVine }
    enum class CanWatchReason { CAN_WATCH_QN_REASON_DEFAULT, CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH, UNKNOWN }
    enum class LimitWatchReason { LIMIT_WATCH_QN_REASON_DEFAULT, LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT, UNRECOGNIZED }
    class PlayIndex(@JvmField var mQuality: Int, var available: Boolean = true) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType: DrmType? = DrmType.No
        @JvmField var qualityInvalid = false
        @JvmField var canWatchReason: CanWatchReason? = null
        @JvmField var limitWatchReason: LimitWatchReason? = null
    }
    class VodIndex(@JvmField var mVodList: ArrayList<PlayIndex>)
    class Resource(@JvmField var mVodIndex: VodIndex) {
        var from = SOURCE_FROM_FLASH
        var resolved = 0
        var hdr = false
        var dolby = false
        var vivid = false
        var drm = false
        var helperCalls = 0
        var writes = 0
        var pairOverride: Pair<Int, Int>? = null
        var throwHelper = false
        var throwWriteOnce = false
        var ignoreWrite = false
        var wrongWriteOnce = false
        var failReadBackOnce = false
        var awaitingReadBack = false
        var dashQns: Set<Int> = emptySet()
        fun sourceFrom() = from
        fun getPlayIndex(): PlayIndex? = mVodIndex.mVodList.getOrNull(resolved)
        fun getResolvedIndex(): Int {
            if (awaitingReadBack && failReadBackOnce) {
                failReadBackOnce = false
                throw IllegalStateException("readback fixture")
            }
            return resolved
        }
        fun setResolvedIndex(index: Int) {
            writes++
            if (ignoreWrite) return
            resolved = if (wrongWriteOnce) 0 else index
            wrongWriteOnce = false
            awaitingReadBack = true
            if (throwWriteOnce) {
                throwWriteOnce = false
                throw IllegalStateException("write fixture")
            }
        }
        fun isHdr() = hdr
        fun isDolbyVision() = dolby
        fun isHdrVivid() = vivid
        fun isDrm() = drm || getPlayIndex()?.mDrmType != DrmType.No
        // 忠实模拟宿主：只判地址/DASH，不判登录、会员、试看或限制原因。
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? {
            helperCalls++
            check(!throwHelper)
            pairOverride?.let { return it }
            var best: Pair<Int, Int>? = null
            mVodIndex.mVodList.forEachIndexed { index, play ->
                if (play.mQuality in 1..target && (play.available || play.mQuality in dashQns) &&
                    play.mQuality > (best?.second ?: 0)) best = index to play.mQuality
            }
            return best
        }
        companion object {
            // 故意不用 1，确保生产实现核静态常量而非猜 sourceFrom 数字。
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
            const val QUALITY_HDRVivid = 302
        }
    }

    private fun access() = requireNotNull(StoryQualityResourceAccess.resolve(
        Resource::class.java, VodIndex::class.java, PlayIndex::class.java))
    private fun resource(vararg qualities: Int) = Resource(VodIndex(qualities.map { PlayIndex(it) }.toCollection(arrayListOf())))

    @Test fun `proposal is read only and application verifies selected index and quality`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        val candidate = requireNotNull(access.selection(resource, 112, login = true, vip = false))
        assertEquals(2, candidate.index)
        assertEquals(80, candidate.qn)
        assertEquals(0, resource.resolved)
        assertEquals(0, resource.writes)
        assertTrue(access.apply(resource, candidate))
        assertEquals(2, resource.resolved)
        assertEquals(80, access.currentQuality(resource))
        assertFalse(access.apply(resource, candidate))
        assertEquals(1, resource.writes)
    }

    @Test fun `source identity uses host constants and unknown source remains unchanged`() {
        val access = access()
        val resource = resource(16, 80)
        assertTrue(access.isPreloaded(resource))
        resource.from = Resource.SOURCE_FROM_NORMAL
        assertFalse(access.isPreloaded(resource))
        assertNotNull(access.selection(resource, 80, true, false))
        resource.from = 1
        assertFalse(access.isPreloaded(resource))
        assertNull(access.selection(resource, 80, true, false))
        assertEquals(0, resource.writes)
    }

    @Test fun `highest available eligible quality respects login and vip independently`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        resource.mVodIndex.mVodList[2].mNeedLogin = true
        resource.mVodIndex.mVodList[3].mNeedVip = true
        assertEquals(64, access.selection(resource, 120, false, false)?.qn)
        assertEquals(80, access.selection(resource, 120, true, false)?.qn)
        assertEquals(120, access.selection(resource, 120, true, true)?.qn)
        assertNull(access.selection(resource, 120, false, false, ownVideo = true))
    }

    @Test fun `try watch and device limit never become rights grants`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        resource.mVodIndex.mVodList[3].canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH
        resource.mVodIndex.mVodList[2].limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT
        assertEquals(64, access.selection(resource, 120, true, true)?.qn)
        resource.mVodIndex.mVodList[0].canWatchReason = CanWatchReason.UNKNOWN
        assertNull(access.selection(resource, 120, true, true))
        assertEquals(0, resource.writes)
    }

    @Test fun `exact default reasons are accepted while invalid or unknown candidate is preserved`() {
        val access = access()
        val resource = resource(16, 80)
        val higher = resource.mVodIndex.mVodList[1]
        higher.canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_DEFAULT
        higher.limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_DEFAULT
        assertEquals(80, access.selection(resource, 80, true, true)?.qn)
        higher.qualityInvalid = true
        assertNull(access.selection(resource, 80, true, true))
        higher.qualityInvalid = false
        higher.limitWatchReason = LimitWatchReason.UNRECOGNIZED
        assertNull(access.selection(resource, 80, true, true))
    }

    @Test fun `special current resources and DRM candidates never change`() {
        val access = access()
        for (setSpecial in listOf<(Resource) -> Unit>({ it.hdr = true }, { it.dolby = true },
            { it.vivid = true }, { it.drm = true })) {
            val resource = resource(16, 80)
            setSpecial(resource)
            assertEquals(true, access.isSpecial(resource))
            assertNull(access.selection(resource, 80, true, true))
            assertEquals(0, resource.writes)
        }
        val resource = resource(16, 80, Resource.QUALITY_HDR)
        resource.mVodIndex.mVodList[1].mDrmType = DrmType.WideVine
        assertNull(access.selection(resource, Resource.QUALITY_HDR, true, true))
        resource.mVodIndex.mVodList[1].mDrmType = null
        assertNull(access.selection(resource, 80, true, true))
        assertNull(access.isSpecial(Any()))
    }

    @Test fun `DASH availability can be used without treating isPlayable as rights check`() {
        val access = access()
        val resource = resource(16, 80)
        resource.mVodIndex.mVodList[1].available = false
        resource.dashQns = setOf(80)
        assertEquals(80, access.selection(resource, 80, true, false)?.qn)
        resource.mVodIndex.mVodList[1].mNeedVip = true
        assertNull(access.selection(resource, 80, true, false))
    }

    @Test fun `host fallback to unavailable candidate is accepted only for another eligible stream`() {
        val access = access()
        val resource = resource(16, 80, 120)
        resource.mVodIndex.mVodList[2].available = false
        assertEquals(80, access.selection(resource, 120, true, false)?.qn)
        val denied = PlayIndex(96).also { it.mNeedVip = true }
        resource.mVodIndex.mVodList.add(denied)
        // 宿主 helper 会越过无地址 120 而命中无权限 96；整提案放行，不能直接写 96。
        assertNull(access.selection(resource, 120, true, false))
    }

    @Test fun `forged host pair cannot bypass quality rights or index correspondence`() {
        val access = access()
        val resource = resource(16, 80, 120)
        resource.mVodIndex.mVodList[2].mNeedVip = true
        for (pair in listOf(1 to 64, 2 to 120, -1 to 80, 9 to 80)) {
            resource.pairOverride = pair
            assertNull(access.selection(resource, 80, true, false))
        }
        assertEquals(0, resource.writes)
    }

    @Test fun `list overflow is rejected before invoking potentially unbounded host helper`() {
        val access = access()
        val resource = Resource(VodIndex(ArrayList(List(129) { PlayIndex(16 + it) })))
        assertNull(access.selection(resource, 200, true, true))
        assertEquals(0, resource.helperCalls)
        resource.mVodIndex.mVodList.removeAt(128)
        assertNotNull(access.selection(resource, 200, true, true))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `malformed list item preserves entire original resource`() {
        val access = access()
        val resource = resource(16, 80)
        (resource.mVodIndex.mVodList as MutableList<Any?>).add(Any())
        assertNull(access.selection(resource, 80, true, false))
        assertEquals(0, resource.helperCalls)
    }

    @Test fun `candidate cannot be applied to another resource or changed list`() {
        val access = access()
        val resource = resource(16, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        assertFalse(access.apply(resource(16, 80), candidate))
        resource.mVodIndex.mVodList = ArrayList(resource.mVodIndex.mVodList)
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.writes)
    }

    @Test fun `rights or original selection changes after proposal prevent any write`() {
        val access = access()
        val resource = resource(16, 64, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        resource.mVodIndex.mVodList[2].mNeedVip = true
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.writes)
        resource.mVodIndex.mVodList[2].mNeedVip = false
        val next = requireNotNull(access.selection(resource, 80, true, false))
        resource.resolved = 1
        assertFalse(access.apply(resource, next))
        assertEquals(1, resource.resolved)
        assertEquals(0, resource.writes)
    }

    @Test fun `setter writing before throwing is restored and never reported as success`() {
        val access = access()
        val resource = resource(16, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        resource.throwWriteOnce = true
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.resolved)
        assertEquals(2, resource.writes)
    }

    @Test fun `silent setter failure and readback error restore original index`() {
        val access = access()
        for (setFailure in listOf<(Resource) -> Unit>({ it.ignoreWrite = true },
            { it.wrongWriteOnce = true }, { it.failReadBackOnce = true })) {
            val resource = resource(16, 80)
            val candidate = requireNotNull(access.selection(resource, 80, true, false))
            setFailure(resource)
            assertFalse(access.apply(resource, candidate))
            assertEquals(0, resource.resolved)
        }
    }

    @Test fun `unknown helper error no op and invalid input keep original behavior`() {
        val access = access()
        val resource = resource(16, 80)
        assertNull(access.selection(resource, 0, true, true))
        assertNull(access.selection(resource, 16, true, true))
        resource.throwHelper = true
        assertNull(access.selection(resource, 80, true, true))
        assertEquals(0, resource.writes)
        assertNull(access.currentQuality(Any()))
        assertNull(StoryQualityResourceAccess.resolve(Any::class.java, VodIndex::class.java, PlayIndex::class.java))
    }

    @Test fun `accepted proves exact current best stream instead of absence of proposal`() {
        val access = access()
        val resource = resource(16, 80)
        assertTrue(access.currentEligible(resource, true, false))
        assertFalse(access.accepted(resource, 80, true, false))
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        assertTrue(access.apply(resource, candidate))
        assertTrue(access.accepted(resource, 80, true, false))
        assertNull(access.selection(resource, 80, true, false))
        resource.throwHelper = true
        assertFalse(access.accepted(resource, 80, true, false))
        assertTrue(access.currentEligible(resource, true, false))
    }

    @Test fun `local accepted lower quality does not claim server target availability`() {
        val access = access()
        val resource = resource(16)
        assertTrue(access.isPreloaded(resource))
        assertEquals(16, access.currentQuality(resource))
        assertTrue(access.currentEligible(resource, true, false))
        assertTrue(access.accepted(resource, 120, true, false))
        assertFalse(access.accepted(resource, 0, true, false))
    }

    @Test fun `current eligibility and accepted both reject unknown rights source and oversized list`() {
        val access = access()
        val resource = resource(16, 80)
        resource.mVodIndex.mVodList[0].limitWatchReason = LimitWatchReason.UNRECOGNIZED
        assertFalse(access.currentEligible(resource, true, true))
        assertFalse(access.accepted(resource, 80, true, true))
        resource.mVodIndex.mVodList[0].limitWatchReason = null
        resource.from = -1
        assertFalse(access.currentEligible(resource, true, true))
        resource.from = Resource.SOURCE_FROM_NORMAL
        repeat(127) { resource.mVodIndex.mVodList.add(PlayIndex(32)) }
        assertFalse(access.currentEligible(resource, true, true))
        assertFalse(access.accepted(resource, 80, true, true))
    }

    enum class LegacyError { WithMultiDeviceLoginErr }
    class LegacyIndex(@JvmField var mQuality: Int) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType = DrmType.No
        @JvmField var mPlayError: LegacyError? = null
    }
    class LegacyVod(@JvmField var mVodList: ArrayList<LegacyIndex>)
    class LegacyResource(@JvmField var mVodIndex: LegacyVod) {
        var resolved = 0
        fun sourceFrom() = SOURCE_FROM_FLASH
        fun getPlayIndex(): LegacyIndex = mVodIndex.mVodList[resolved]
        fun getResolvedIndex() = resolved
        fun setResolvedIndex(index: Int) { resolved = index }
        fun isHdr() = false
        fun isDolbyVision() = false
        fun isDrm() = false
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? = mVodIndex.mVodList.withIndex()
            .filter { it.value.mQuality <= target }.maxByOrNull { it.value.mQuality }?.let { it.index to it.value.mQuality }
        companion object {
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
        }
    }

    @Test fun `legacy play error is independent of modern nullable rights shape`() {
        val access = requireNotNull(StoryQualityResourceAccess.resolve(LegacyResource::class.java,
            LegacyVod::class.java, LegacyIndex::class.java))
        val resource = LegacyResource(LegacyVod(arrayListOf(LegacyIndex(16), LegacyIndex(80))))
        assertEquals(80, access.selection(resource, 80, true, false)?.qn)
        resource.mVodIndex.mVodList[1].mPlayError = LegacyError.WithMultiDeviceLoginErr
        assertNull(access.selection(resource, 80, true, false))
        resource.mVodIndex.mVodList[1].mPlayError = null
        resource.mVodIndex.mVodList[0].mPlayError = LegacyError.WithMultiDeviceLoginErr
        assertNull(access.selection(resource, 80, true, false))
    }
}
