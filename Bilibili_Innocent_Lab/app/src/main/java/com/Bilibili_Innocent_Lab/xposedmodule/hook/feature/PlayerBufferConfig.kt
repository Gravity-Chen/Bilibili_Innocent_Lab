package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

internal enum class PlayerBufferMode(val value: Int) {
    FOLLOW_HOST(0),
    LOW(1),
    CUSTOM(2),
    WHOLE(3);

    companion object {
        fun fromValue(value: Int): PlayerBufferMode = entries.firstOrNull { it.value == value } ?: FOLLOW_HOST
    }
}

/** Persist seconds and MiB; convert to native units only after validating the complete policy. */
internal object PlayerBufferConfig {
    const val DEFAULT_SECONDS = 30
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 600
    const val DEFAULT_MEMORY_MB = 64
    const val MIN_MEMORY_MB = 16
    const val MAX_MEMORY_MB = 256
    const val DEFAULT_DISK_MB = 1_024
    const val MIN_DISK_MB = 256
    const val MAX_DISK_MB = 8_192
    const val DEFAULT_WIFI_ONLY = true
    const val MIB = 1_048_576L

    val supportedModes: Set<Int> = PlayerBufferMode.entries.mapTo(linkedSetOf()) { it.value }

    fun normalizeSeconds(value: Int): Int = value.takeIf { it in MIN_SECONDS..MAX_SECONDS } ?: DEFAULT_SECONDS
    fun normalizeMemoryMb(value: Int): Int = value.takeIf { it in MIN_MEMORY_MB..MAX_MEMORY_MB } ?: DEFAULT_MEMORY_MB
    fun normalizeDiskMb(value: Int): Int = value.takeIf { it in MIN_DISK_MB..MAX_DISK_MB } ?: DEFAULT_DISK_MB
    fun mode(value: Int): PlayerBufferMode = PlayerBufferMode.fromValue(value)

    fun secondsToMillis(value: Int): Long? = value.takeIf { it in MIN_SECONDS..MAX_SECONDS }?.toLong()?.times(1_000L)
    fun memoryBytes(value: Int): Long? = value.takeIf { it in MIN_MEMORY_MB..MAX_MEMORY_MB }?.toLong()?.times(MIB)
    fun diskBytes(value: Int): Long? = value.takeIf { it in MIN_DISK_MB..MAX_DISK_MB }?.toLong()?.times(MIB)
}
