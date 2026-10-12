package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一个最新路径、一份空间快照、至多一个在飞采样，不持有播放器或宿主 Context。
 * [schedule] 必须只把任务交给调用方的后台队列；本类不创建线程，也不操作缓存文件。
 * 播放回调只调用 [request]/[available]，StatFs 和父目录解析只在采样任务内执行。
 */
internal class PlayerBufferDiskBudget(
    private val configuredBytes: Long,
    private val schedule: (() -> Unit) -> Boolean,
    private val availableBytes: (String) -> Long = { StatFs(it).availableBytes },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Sample(val cacheFilePath: String, val measuredAtMs: Long, val safeBytes: Long?)

    private val desiredPath = AtomicReference<String?>()
    private val sampleInFlight = AtomicBoolean(false)
    private val latest = AtomicReference<Sample?>()

    /** 只合并请求和入队；缺少新鲜采样时由调用方保留宿主原策略。 */
    fun request(cacheFilePath: String) {
        if (configuredBytes <= 0L || cacheFilePath.isBlank()) return
        desiredPath.set(cacheFilePath)
        // 失败采样也短暂缓存，避免每次播放器调用都重新探测失败的文件系统。
        if (freshSample(cacheFilePath) != null || !sampleInFlight.compareAndSet(false, true)) return
        if (!runCatching { schedule(::measureLatest) }.getOrDefault(false)) sampleInFlight.set(false)
    }

    /** 只有最新请求的同一路径、30 秒内的已测值可用；失败和过期均返回 null。 */
    fun available(cacheFilePath: String): Long? {
        if (configuredBytes <= 0L || desiredPath.get() != cacheFilePath) return null
        return freshSample(cacheFilePath)?.safeBytes
    }

    private fun freshSample(cacheFilePath: String): Sample? {
        val current = latest.get()?.takeIf { it.cacheFilePath == cacheFilePath } ?: return null
        val now = runCatching(nowMs).getOrNull() ?: return null
        if (now < current.measuredAtMs || now - current.measuredAtMs > MAX_SAMPLE_AGE_MS) return null
        return current
    }

    private fun measureLatest() {
        val path = desiredPath.get()
        try {
            if (path == null || freshSample(path) != null) return
            val safeBytes = runCatching {
                val directory = File(path).parent ?: return@runCatching null
                val free = availableBytes(directory)
                if (free < 0L) return@runCatching null
                // 先除再减，Long.MAX_VALUE 也不会溢出；低空间时预算为零，不产生负值。
                minOf(configuredBytes, (free / 2L - FREE_SPACE_RESERVE_BYTES).coerceAtLeast(0L))
            }.getOrNull()
            val measuredAt = runCatching(nowMs).getOrNull()?.takeIf { it >= 0L }
            latest.set(measuredAt?.let { Sample(path, it, safeBytes) })
        } finally {
            sampleInFlight.set(false)
            // 在飞期间的切路径请求只保留最新一个；旧路径的快照不能供新路径使用。
            desiredPath.get()?.takeIf { it != path }?.let(::request)
        }
    }

    companion object {
        const val MAX_SAMPLE_AGE_MS = 30_000L
        private const val FREE_SPACE_RESERVE_BYTES = 128L * 1_048_576L
    }
}
