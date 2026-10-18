package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamingSettingsLaunchPolicyTest {
    private val hostPackage = "tv.danmaku.bili"
    private val hostUid = 10_123
    private val nonce = "4d8d066a-2b31-49ca-a1f2-844576f7e756"
    private val otherNonce = "6a43ff24-083f-40ad-8f29-75a2f6491131"

    @Test fun freshnessIncludesTheFiveSecondBoundary() {
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(1_000L, 1_000L))
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(1_000L, 6_000L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1_000L, 6_001L))
    }

    @Test fun invalidAndFutureTimestampsNeverBecomeFreshThroughOverflow() {
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(0L, 0L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(-1L, 1L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1_000L, 999L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(Long.MAX_VALUE, Long.MIN_VALUE))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1L, Long.MAX_VALUE))
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(Long.MAX_VALUE - 5_000L, Long.MAX_VALUE))
    }

    @Test fun oldAndroidStillRequiresTheHostCreatorIdentity() {
        for (sdk in listOf(27, 30, 31, 33)) {
            assertTrue(accepts(sdk = sdk, sentFromPackage = null, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, creatorPackage = "other.app", sentFromPackage = null, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, creatorUid = hostUid + 1, sentFromPackage = null, sentFromUid = -1))
        }
    }

    @Test fun missingOrInvalidExpectedAndCreatorIdentitiesAreRejected() {
        assertFalse(accepts(expectedPackage = ""))
        assertFalse(accepts(expectedPackage = " "))
        assertFalse(accepts(expectedUid = -1, creatorUid = -1, sentFromUid = -1))
        assertFalse(accepts(expectedUid = 0, creatorUid = 0, sentFromUid = 0))
        assertFalse(accepts(creatorPackage = null))
        assertFalse(accepts(creatorUid = -1))
    }

    @Test fun modernAndroidRequiresTheSystemSenderToMatchTheHostProof() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertTrue(accepts(sdk = sdk))
            assertFalse(accepts(sdk = sdk, sentFromPackage = null))
            assertFalse(accepts(sdk = sdk, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, sentFromPackage = "other.app"))
            assertFalse(accepts(sdk = sdk, sentFromUid = hostUid + 1))
            assertFalse(accepts(sdk = sdk, creatorPackage = "other.app"))
            assertFalse(accepts(sdk = sdk, creatorUid = hostUid + 1))
        }
    }

    @Test fun senderModesRespectThePlatformApiBoundaries() {
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.PLATFORM_DEFAULT, RoamingSettingsLaunchPolicy.senderMode(27))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.PLATFORM_DEFAULT, RoamingSettingsLaunchPolicy.senderMode(33))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_VISIBLE_CALLER, RoamingSettingsLaunchPolicy.senderMode(34))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_VISIBLE_CALLER, RoamingSettingsLaunchPolicy.senderMode(35))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_IF_VISIBLE, RoamingSettingsLaunchPolicy.senderMode(36))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_IF_VISIBLE, RoamingSettingsLaunchPolicy.senderMode(37))
    }

    @Test fun aMatchingFreshVisibleResponseCanOnlyBeConsumedOnce() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertTrue(lease.consume(nonce, 6_000L, true))
        assertFalse(lease.consume(nonce, 6_000L, true))
        assertFalse(lease.close())
    }

    @Test fun unrelatedOrMissingResponseNoncesDoNotConsumeTheCurrentClick() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(lease.consume(null, 1_000L, true))
        assertFalse(lease.consume(otherNonce, 1_000L, true))
        assertFalse(lease.consume("invalid", 1_000L, true))
        assertTrue(lease.consume(nonce, 1_000L, true))
    }

    @Test fun leasesRejectNonCanonicalOrMalformedUuidNonces() {
        for (invalid in listOf("", "nonce", "a".repeat(36), "1-2-3-4-5", nonce.replace('-', '_'), "$nonce ")) {
            val lease = RoamingSettingsLaunchLease(invalid, 1_000L)
            assertFalse(lease.consume(invalid, 1_000L, true))
            assertFalse(lease.close())
        }
        val upper = nonce.uppercase()
        assertTrue(RoamingSettingsLaunchLease(upper, 1_000L).consume(upper, 1_000L, true))
    }

    @Test fun expiredOrFutureResponsesCloseTheLeasePermanently() {
        val expired = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(expired.consume(nonce, 6_001L, true))
        assertFalse(expired.consume(nonce, 1_000L, true))
        assertFalse(expired.close())
        val future = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(future.consume(nonce, 999L, true))
        assertFalse(future.consume(nonce, 1_000L, true))
    }

    @Test fun losingVisibilityCannotBeUndoneByReturningToTheApp() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(lease.consume(nonce, 1_000L, false))
        assertFalse(lease.consume(nonce, 1_001L, true))
        assertFalse(lease.close())
    }

    @Test fun closingBeforeTheResponseIsIdempotentAndFinal() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertTrue(lease.close())
        assertFalse(lease.close())
        assertFalse(lease.consume(nonce, 1_000L, true))
    }

    @Test fun racingResponsesLaunchAtMostOnce() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                pool.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    lease.consume(nonce, 1_000L, true)
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun closingAndConsumingCompeteForTheSameLease() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val consume = pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lease.consume(nonce, 1_000L, true)
            })
            val close = pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lease.close()
            })
            start.countDown()
            assertEquals(1, listOf(consume.get(5, TimeUnit.SECONDS), close.get(5, TimeUnit.SECONDS)).count { it })
            assertFalse(lease.consume(nonce, 1_000L, true))
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun filteredCreatorRequiresAnAlreadyVerifiedPrivateReply() {
        val modulePackage = "com.example.module"
        assertTrue(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, null, true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, null, false))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, modulePackage, false))
    }

    @Test fun privateReplyStillRejectsAnExplicitlyWrongCreator() {
        val modulePackage = "com.example.module"
        assertTrue(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, modulePackage, true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, "com.example.other", true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, "", true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator("", null, true))
    }

    private fun accepts(
        expectedPackage: String = hostPackage,
        expectedUid: Int = hostUid,
        creatorPackage: String? = hostPackage,
        creatorUid: Int = hostUid,
        sdk: Int = 34,
        sentFromPackage: String? = hostPackage,
        sentFromUid: Int = hostUid
    ): Boolean = RoamingSettingsLaunchPolicy.acceptsCaller(
        expectedPackage, expectedUid, creatorPackage, creatorUid, sdk, sentFromPackage, sentFromUid
    )
}
