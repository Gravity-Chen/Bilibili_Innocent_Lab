package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.concurrent.atomic.AtomicBoolean

internal object RoamingSettingsLaunchPolicy {
    const val MAX_REQUEST_AGE_MS = 5_000L
    const val MIN_REQUEST_INTERVAL_MS = 1_000L

    enum class SenderMode { PLATFORM_DEFAULT, ALLOW_VISIBLE_CALLER, ALLOW_IF_VISIBLE }

    fun isFresh(requestedAt: Long, now: Long): Boolean =
        requestedAt > 0L && now >= requestedAt && now - requestedAt <= MAX_REQUEST_AGE_MS

    fun acceptsCaller(
        expectedPackage: String,
        expectedUid: Int,
        creatorPackage: String?,
        creatorUid: Int,
        sdk: Int,
        sentFromPackage: String?,
        sentFromUid: Int
    ): Boolean =
        expectedPackage.isNotBlank() && expectedUid > 0 &&
            creatorPackage == expectedPackage && creatorUid == expectedUid &&
            (sdk < 34 || (sentFromPackage == expectedPackage && sentFromUid == expectedUid))

    fun senderMode(sdk: Int): SenderMode = when {
        sdk >= 36 -> SenderMode.ALLOW_IF_VISIBLE
        sdk >= 34 -> SenderMode.ALLOW_VISIBLE_CALLER
        else -> SenderMode.PLATFORM_DEFAULT
    }

    fun acceptsPrivateReplyCreator(
        expectedModulePackage: String,
        reportedCreatorPackage: String?,
        privateReplyVerified: Boolean
    ): Boolean = privateReplyVerified && expectedModulePackage.isNotBlank() &&
        (reportedCreatorPackage == null || reportedCreatorPackage == expectedModulePackage)
}

/** 点击只允许消费一次；退出页面、过期或主动关闭后不再接受迟到回执。 */
internal class RoamingSettingsLaunchLease(val nonce: String, val requestedAt: Long) {
    private val open = AtomicBoolean(true)

    fun consume(responseNonce: String?, now: Long, visible: Boolean): Boolean {
        if (responseNonce != nonce) return false
        if (!NONCE_REGEX.matches(nonce) || !RoamingSettingsLaunchPolicy.isFresh(requestedAt, now) || !visible) {
            close()
            return false
        }
        return open.compareAndSet(true, false)
    }

    fun close(): Boolean = open.compareAndSet(true, false)

    private companion object {
        val NONCE_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
