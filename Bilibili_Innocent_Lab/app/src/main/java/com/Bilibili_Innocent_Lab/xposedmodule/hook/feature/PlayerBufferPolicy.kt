package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 只决定有限的 native 选项；不读取宿主对象、磁盘或网络。 */
internal object PlayerBufferPolicy {
    const val HEADROOM_BYTES = PlayerBufferConfig.MIB

    data class Settings(
        val mode: PlayerBufferMode,
        val seconds: Int,
        val memoryMb: Int,
        val diskMb: Int,
        val wifiOnly: Boolean
    )

    /** eligible 和 progressive 必须由安装器完成归属与播放路由验证。 */
    data class Media(
        val current: Boolean,
        val eligible: Boolean,
        val progressive: Boolean,
        val fileSize: Long,
        val durationMs: Long
    )

    data class Decision(
        val itemTimeMs: Long?,
        val itemBytes: Long?,
        val consumerBytes: Long?,
        val forwardBytes: Long?,
        val diskCapacity: Long?,
        val reason: String
    ) {
        val active: Boolean
            get() = itemTimeMs != null && itemBytes != null && consumerBytes != null

        val fullPreload: Boolean
            get() = active && forwardBytes != null && diskCapacity != null
    }

    fun decide(
        settings: Settings,
        media: Media,
        verifiedDiskBytes: Long?,
        wifi: Boolean
    ): Decision {
        if (settings.mode == PlayerBufferMode.FOLLOW_HOST) return disabled("follow-host")
        val configuredTimeMs = PlayerBufferConfig.secondsToMillis(settings.seconds)
            ?: return disabled("invalid-settings")
        val memoryBytes = PlayerBufferConfig.memoryBytes(settings.memoryMb)
            ?: return disabled("invalid-settings")
        val configuredDiskBytes = PlayerBufferConfig.diskBytes(settings.diskMb)
            ?: return disabled("invalid-settings")
        if (!media.current) return disabled("not-current")
        if (!media.eligible) return disabled("not-applicable")

        val itemTimeMs = when (settings.mode) {
            PlayerBufferMode.LOW -> 1_000L
            PlayerBufferMode.CUSTOM -> configuredTimeMs
            PlayerBufferMode.WHOLE -> PlayerBufferConfig.DEFAULT_SECONDS * 1_000L
            PlayerBufferMode.FOLLOW_HOST -> return disabled("follow-host")
        }
        // 两份独立包队列共享用户设置的预算，不把每一层都扩大到总预算。
        val itemBytes = memoryBytes / 2L
        val phaseOne = Decision(
            itemTimeMs = itemTimeMs,
            itemBytes = itemBytes,
            consumerBytes = memoryBytes - itemBytes,
            forwardBytes = null,
            diskCapacity = null,
            reason = when (settings.mode) {
                PlayerBufferMode.LOW -> "low"
                PlayerBufferMode.CUSTOM -> "custom"
                else -> "whole-fallback"
            }
        )
        if (settings.mode != PlayerBufferMode.WHOLE) return phaseOne
        if (!media.progressive) return phaseOne.copy(reason = "unsupported-disk-route")
        if (media.fileSize <= 0L) return phaseOne.copy(reason = "unknown-media-size")
        if (media.durationMs <= 0L) return phaseOne.copy(reason = "unknown-media-duration")
        if (settings.wifiOnly && !wifi) return phaseOne.copy(reason = "wifi-required")
        val diskBytes = verifiedDiskBytes?.takeIf { it > 0L }
            ?: return phaseOne.copy(reason = "disk-budget-unverified")
        val safeBudget = minOf(configuredDiskBytes, diskBytes)
        // native 在 physicalPos + size >= capacity 时清空缓存；相减校验避免 size 溢出。
        if (safeBudget <= HEADROOM_BYTES || media.fileSize > safeBudget - HEADROOM_BYTES) {
            return phaseOne.copy(reason = "insufficient-disk-budget")
        }
        return phaseOne.copy(
            forwardBytes = media.fileSize,
            diskCapacity = safeBudget,
            reason = "whole"
        )
    }

    private fun disabled(reason: String) = Decision(null, null, null, null, null, reason)
}
