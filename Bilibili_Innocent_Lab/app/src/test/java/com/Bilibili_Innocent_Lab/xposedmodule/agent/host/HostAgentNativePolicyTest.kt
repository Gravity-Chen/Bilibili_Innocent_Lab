package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.junit.Assert.*
import org.junit.Test

class HostAgentNativePolicyTest {
    private fun lease(session: HostAgentSession) = checkNotNull(session.begin("native_task_123", 1, 1000, 0, false).lease)

    @Test fun accountReadsRequireExplicitAuthorizationAndStableCurrentAccount() {
        val session = HostAgentSession()
        val lease = lease(session)
        assertEquals("account_read_not_authorized", session.accountRefusal(lease, 42, 1))
        session.accountAuthorization(lease, true, 42, 1)
        assertNull(session.accountRefusal(lease, 42, 2))
        assertEquals("account_changed", session.accountRefusal(lease, 43, 2))
        assertEquals("account_identity_unavailable", session.accountRefusal(lease, null, 2))
        session.cancel(lease, 3)
        assertEquals("task_inactive", session.accountRefusal(lease, 42, 4))
    }

    @Test fun authorizedButUnavailableIdentityDoesNotAuthorizePersonalData() {
        val session = HostAgentSession()
        val lease = lease(session)
        session.accountAuthorization(lease, true, null, 1)
        assertEquals("account_identity_unavailable", session.accountRefusal(lease, 42, 2))
    }

    @Test fun observedUserAndRegionIdsAreTaskScopedAndCanonical() {
        val session = HostAgentSession()
        val lease = lease(session)
        session.rememberUsers(lease, listOf("42", "042", "0", "-1", "9223372036854775808"), 1)
        assertTrue(session.knowsUser(lease, "42", 2))
        assertFalse(session.knowsUser(lease, "042", 2))
        assertTrue(session.knowsRegion(lease, "0", 2))
        assertFalse(session.knowsRegion(lease, "17", 2))
        session.rememberRegions(lease, listOf("17"), 2)
        assertTrue(session.knowsRegion(lease, "17", 3))
        session.cancel(lease, 4)
        assertFalse(session.knowsUser(lease, "42", 5))
    }

    @Test fun pageTokensDoNotCrossDatasetOrUserBoundaries() {
        val session = HostAgentSession()
        val lease = lease(session)
        val token = checkNotNull(session.rememberCursor(lease, "get_user_videos:42", "private_cursor", 1))
        assertEquals("private_cursor", session.cursor(lease, "get_user_videos:42", token, 2))
        assertNull(session.cursor(lease, "get_user_videos:43", token, 2))
        assertNull(session.cursor(lease, "get_ranking:0", token, 2))
    }

    @Test fun closingLeaseDoesNotEraseWindowTakeoverEvidenceWhileReleaseIsQueued() {
        val session = HostAgentSession()
        val lease = lease(session)
        val window = HostAgentWindowPolicy()
        window.begin(lease.generation, 42)
        assertNotNull(session.admit(lease.taskId, 2, 1000, 1, closing = true).lease)
        assertFalse(session.isTaskActive(lease, 2))
        assertTrue(window.ownsWindow(lease.generation, 42))
        assertFalse(window.ownsWindow(lease.generation, 43))
        window.revoke(lease.generation)
        assertFalse(window.ownsWindow(lease.generation, 42))
    }

    @Test fun absentAndNegativeMetricsRemainUnknownWhileExplicitZeroIsValid() {
        val reader = checkNotNull(HostAgentVideoStatistics.resolve(Arc::class.java))
        assertEquals("unknown", reader.read(Arc(false, Stats(0, 0))).getString("status"))
        assertEquals("unknown", reader.read(Arc(true, Stats.getDefaultInstance())).getString("status"))
        val explicitZero = reader.read(Arc(true, Stats(0, 0)))
        assertEquals("reported", explicitZero.getString("status"))
        assertEquals(0, explicitZero.getLong("view_count"))
        val data = reader.read(Arc(true, Stats(0, -1)))
        assertEquals(0, data.getLong("view_count"))
        assertTrue(data.isNull("like_count"))
        assertTrue(data.isNull("coin_count"))
    }

    class Arc(private val present: Boolean, private val value: Stats) {
        fun hasStat() = present
        fun getStat() = value
    }
    class Stats(private val view: Int, private val like: Int) {
        fun getView() = view
        fun getLike() = like
        override fun equals(other: Any?): Boolean = other is Stats && view == other.view && like == other.like
        override fun hashCode(): Int = 31 * view + like
        companion object {
            private val DEFAULT = Stats(0, 0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }
}
