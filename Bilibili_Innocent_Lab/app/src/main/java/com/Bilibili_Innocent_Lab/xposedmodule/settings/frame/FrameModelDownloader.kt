package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import android.content.Context
import android.os.ParcelFileDescriptor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameModelSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块设置中的「下载插帧模型」：仅在用户主动点击后执行，从固定提交的官方仓库下载 RIFE v4.6 两个文件，
 * 逐文件校验大小与 SHA-256，全部通过后写入 LSPosed 远程文件供宿主进程读取。模块本身不分发权重。
 * 必须在后台线程调用；进度通过回调报告，失败不会留下半个模型。
 */
internal object FrameModelDownloader {
    sealed interface Progress {
        data class Downloading(val part: String, val downloaded: Long, val total: Long) : Progress
        data object Publishing : Progress
        data object Done : Progress
        data class Failed(val reason: String) : Progress
    }

    enum class State { MISSING, READY, FRAMEWORK_UNAVAILABLE }

    /** 检查远程文件是否齐全且校验通过（读两个文件约 10 MB，需在后台线程调用）。 */
    fun state(): State = RemoteHookConfigStore.withService { service ->
        val ready = FrameModelSpec.PARTS.all { part ->
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(part.remoteName)).buffered().use {
                    FrameModelSpec.matches(part, it)
                }
            }.getOrDefault(false)
        }
        if (ready) State.READY else State.MISSING
    } ?: State.FRAMEWORK_UNAVAILABLE

    fun download(context: Context, progress: (Progress) -> Unit): Boolean {
        val folder = File(context.cacheDir, "frame-model-download").apply { mkdirs() }
        val downloaded = HashMap<FrameModelSpec.Part, File>()
        try {
            for (part in FrameModelSpec.PARTS) {
                val file = File(folder, part.fileName)
                if (!fetch(part, file, progress)) { progress(Progress.Failed("download:${part.fileName}")); return false }
                downloaded[part] = file
            }
            progress(Progress.Publishing)
            val published = RemoteHookConfigStore.withService { service ->
                FrameModelSpec.PARTS.all { part ->
                    val source = downloaded.getValue(part)
                    service.openRemoteFile(part.remoteName).use { descriptor ->
                        FileOutputStream(descriptor.fileDescriptor).use { output ->
                            output.channel.truncate(0)
                            source.inputStream().use { it.copyTo(output, 64 * 1024) }
                            output.fd.sync()
                        }
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(part.remoteName)).buffered().use {
                        FrameModelSpec.matches(part, it)
                    }
                }
            }
            return when (published) {
                true -> { progress(Progress.Done); true }
                false -> { progress(Progress.Failed("verify")); false }
                null -> { progress(Progress.Failed("framework")); false }
            }
        } finally {
            folder.listFiles()?.forEach { it.delete() }
        }
    }

    private fun fetch(part: FrameModelSpec.Part, target: File, progress: (Progress) -> Unit): Boolean {
        val connection = URL(part.sourceUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "BilibiliInnocentLab")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            var total = 0L
            connection.inputStream.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    FrameModelSpec.matches(part, input) { bytes, count ->
                        output.write(bytes, 0, count)
                        total += count
                        progress(Progress.Downloading(part.fileName, total, part.size.toLong()))
                    }
                }
            }.also { if (!it) target.delete() }
        } catch (failure: Exception) {
            target.delete()
            false
        } finally {
            connection.disconnect()
        }
    }
}
