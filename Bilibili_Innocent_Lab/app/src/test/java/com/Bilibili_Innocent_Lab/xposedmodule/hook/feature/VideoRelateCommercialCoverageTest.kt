package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bapis.bilibili.app.viewunite.common.CardBasicInfo
import com.bapis.bilibili.app.viewunite.common.BadgeInfo
import com.bapis.bilibili.app.viewunite.common.RelateAVCard
import com.bapis.bilibili.app.viewunite.common.RelateCard
import com.bapis.bilibili.app.viewunite.common.RelateThreePoint
import com.bapis.bilibili.app.viewunite.common.Relates
import org.junit.Assert.*
import org.junit.Test

class VideoRelateCommercialCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private class Card(
        private val stock: Boolean = false,
        private val feedback: Boolean = false,
        private val failStock: Boolean = false,
        private val case: RelateCard.CardCase = RelateCard.CardCase.AV
    ) : RelateCard() {
        override fun getCardCase() = case
        override fun getAv(): RelateAVCard = object : RelateAVCard() {
            override fun getRcmdReason(): BadgeInfo = object : BadgeInfo() {
                override fun getText() = "3万点赞"
            }
        }
        override fun getBasicInfo(): CardBasicInfo = object : CardBasicInfo() {
            override fun getFromSourceType() = 0L
        }
        override fun hasCmStock(): Boolean {
            check(!failStock)
            return stock
        }
        override fun getThreePoint(): RelateThreePoint = object : RelateThreePoint() {
            override fun hasFeedback() = feedback
        }
    }

    private inner class Harness(
        hidden: Set<String>,
        enhancement: Boolean = false,
        strong: Boolean = false,
        typeReaders: Boolean = true,
        missingStockReader: Boolean = false
    ) {
        val registrar = ContentFilterTestRegistrar(loader)
        val statuses = mutableListOf<String>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter {
                it.className == Relates::class.java.name
            },
            detailRelateService = null,
            // 本夹具是 AV；其余卡型的默认样本带推广理由，不属于这条反馈证据测试的输入。
            reasonChains = located.reasonChains.filter { it.steps.first().methodName == "getAv" },
            commercialEvidenceChains = located.commercialEvidenceChains.map { chain ->
                if (missingStockReader && chain.steps.singleOrNull()?.methodName == "hasCmStock") {
                    chain.copy(steps = listOf(chain.steps.single().copy(className = "missing.host.RelateCard")))
                } else chain
            },
            cardCaseGetters = if (typeReaders) located.cardCaseGetters else emptyList(),
            gotoGetters = if (typeReaders) located.gotoGetters else emptyList(),
            cardTypeGetters = if (typeReaders) located.cardTypeGetters else emptyList(),
            relateCardTypeGetters = if (typeReaders) located.relateCardTypeGetters else emptyList(),
            fromSourceTypeGetters = if (typeReaders) located.fromSourceTypeGetters else emptyList(),
            fromSourceTypeChains = if (typeReaders) located.fromSourceTypeChains else emptyList(),
            relateCardTypeValueGetters = if (typeReaders) located.relateCardTypeValueGetters else emptyList()
        )
        val result = VideoRelateFilterFeatureInstaller(
            hiddenTypes = hidden, minDurationSeconds = 0, maxDurationSeconds = 0,
            matchingEnhancementEnabled = enhancement, strongModeEnabled = strong, points = points
        ).install(HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, status -> statuses += status }
        ))

        fun filter(source: List<*>) = registrar.items("video.relate.response.0", Relates(), source)
    }

    @Test fun explicitCommercialPayloadWorksWithoutEnhancementOrStrongMode() {
        for (type in listOf("CM", "SPECIAL")) {
            val h = Harness(setOf(type))
            val normal = Card()
            val source = listOf(Card(stock = true), normal)
            assertEquals(type, listOf(normal), h.filter(source))
            assertEquals(2, source.size)
        }
    }

    @Test fun normalFeedbackIsNotPromotedToCommercialEvidenceOutsideStrongMode() {
        for (enhancement in listOf(false, true)) {
            val h = Harness(setOf("CM"), enhancement = enhancement)
            val source = listOf(Card(feedback = true))
            assertSame(source, h.filter(source))
        }
    }

    @Test fun gameOnlyDoesNotAcquireCommercialRemovalFromAnotherDimension() {
        val h = Harness(setOf("GAME"))
        val source = listOf(Card(stock = true))
        assertSame(source, h.filter(source))
    }

    @Test fun payloadFallbackContinuesWhenTypeReadersAreMissing() {
        val h = Harness(setOf("CM"), typeReaders = false)
        assertTrue(h.result is FeatureInstallResult.Installed)
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(stock = true), normal)))
    }

    @Test fun failingPayloadReaderDoesNotRemoveUnknownVideo() {
        val h = Harness(setOf("CM"))
        val source = listOf(Card(failStock = true))
        assertSame(source, h.filter(source))
    }

    @Test fun missingPayloadReaderReportsPartialButTypedCommercialFilteringContinues() {
        val h = Harness(setOf("CM"), missingStockReader = true)
        assertTrue(h.statuses.single().contains("missing-commercial-payload-accessor"))
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(case = RelateCard.CardCase.CM), normal)))
    }
}
