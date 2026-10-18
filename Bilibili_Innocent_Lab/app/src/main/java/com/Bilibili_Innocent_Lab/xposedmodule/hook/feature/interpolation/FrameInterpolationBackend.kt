package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Optional backend only; no model is bundled and no player is intercepted by this class. */
internal class FrameInterpolationBackend private constructor(private var handle: Long) : AutoCloseable {
    enum class Backend(val nativeValue: Int) { CPU_RESEARCH(0), VULKAN(1) }
    enum class Status(val nativeValue: Int) {
        OK(0), INVALID_ARGUMENT(1), UNSUPPORTED_BACKEND(2), GPU_UNAVAILABLE(3), MODEL_TOO_LARGE(4),
        INVALID_MODEL(5), MODEL_LOAD_FAILED(6), NOT_LOADED(7), BUSY(8), ALLOCATION_FAILED(9),
        INFERENCE_FAILED(10), INVALID_OUTPUT(11), NATIVE_UNAVAILABLE(-1);

        companion object {
            fun fromNative(value: Int): Status = entries.firstOrNull { it.nativeValue == value } ?: INFERENCE_FAILED
        }
    }

    data class OpenResult(val status: Status, val engine: FrameInterpolationBackend? = null)

    /** The caller owns buffers and serializes their mutation on a bounded background worker. */
    @Synchronized fun process(
        left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
        width: Int, height: Int, timestep: Float,
        leftStride: Int = width * 3, rightStride: Int = width * 3, outputStride: Int = width * 3
    ): Status {
        requireWorker()
        if (handle == 0L) return Status.NOT_LOADED
        if (!timestep.isFinite() || timestep !in 0f..1f || output.isReadOnly ||
            !planeValid(left, width, height, leftStride) || !planeValid(right, width, height, rightStride) ||
            !planeValid(output, width, height, outputStride)) return Status.INVALID_ARGUMENT
        return Status.fromNative(
            FrameInterpolationNative.process(handle, left, right, output, width, height,
                leftStride, rightStride, outputStride, timestep)
        )
    }

    /** Schedule on the engine worker after revoking presentation epochs. */
    @Synchronized override fun close() {
        requireWorker()
        val previous = handle
        handle = 0
        if (previous != 0L) FrameInterpolationNative.close(previous)
    }

    companion object {
        private const val MAX_PIXELS = 1920L * 1080L
        private const val PARAM_SIZE = 16_532
        private const val WEIGHTS_SIZE = 10_614_320
        private const val PARAM_HASH = "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c"
        private const val WEIGHTS_HASH = "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958"

        /** Research caller supplies the exact reviewed files. No network, download or model discovery. */
        fun openResearchModel(directory: File, backend: Backend, threads: Int = 1): OpenResult {
            requireWorker()
            if (threads !in 1..4) return OpenResult(Status.INVALID_ARGUMENT)
            if (!FrameInterpolationNative.available) return OpenResult(Status.NATIVE_UNAVAILABLE)
            val model = runCatching {
                val root = directory.canonicalFile
                require(root.isDirectory)
                Pair(readVerified(root, "flownet.param", PARAM_SIZE, PARAM_HASH),
                    readVerified(root, "flownet.bin", WEIGHTS_SIZE, WEIGHTS_HASH))
            }.getOrElse { return OpenResult(if (it is OutOfMemoryError) Status.ALLOCATION_FAILED else Status.INVALID_MODEL) }
            val created = runCatching { FrameInterpolationNative.create(backend.nativeValue, 0, threads) }
                .getOrElse { return OpenResult(Status.NATIVE_UNAVAILABLE) }
            if (created.size != 2 || created[0] != 0L || created[1] <= 0L)
                return OpenResult(if (created.firstOrNull() == 0L) Status.ALLOCATION_FAILED
                    else Status.fromNative(created.firstOrNull()?.toInt() ?: -1))
            val handle = created[1]
            val loaded = runCatching {
                Status.fromNative(FrameInterpolationNative.load(handle, model.first, model.second))
            }.getOrElse { Status.MODEL_LOAD_FAILED }
            if (loaded != Status.OK) {
                runCatching { FrameInterpolationNative.close(handle) }
                return OpenResult(loaded)
            }
            return try {
                OpenResult(Status.OK, FrameInterpolationBackend(handle))
            } catch (failure: Throwable) {
                // Transfer ownership only after both JVM owner/result allocations succeed.
                runCatching { FrameInterpolationNative.close(handle) }
                if (failure is OutOfMemoryError) OpenResult(Status.ALLOCATION_FAILED) else throw failure
            }
        }

        private fun readVerified(root: File, name: String, expectedSize: Int, expectedHash: String): ByteBuffer {
            val file = File(root, name).canonicalFile
            require(file.parentFile == root && file.isFile && file.length() == expectedSize.toLong())
            val buffer = ByteBuffer.allocateDirect(expectedSize)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(count <= expectedSize - total)
                    buffer.put(chunk, 0, count)
                    digest.update(chunk, 0, count)
                    total += count
                }
                require(total == expectedSize)
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualHash == expectedHash)
            buffer.flip()
            return buffer
        }

        private fun planeValid(buffer: ByteBuffer, width: Int, height: Int, stride: Int): Boolean {
            if (!buffer.isDirect || buffer.position() != 0 || width <= 0 || height <= 0 ||
                width.toLong() * height > MAX_PIXELS || stride.toLong() < width.toLong() * 3) return false
            return (height - 1L) * stride + width.toLong() * 3 <= buffer.limit().toLong()
        }

        private fun requireWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Frame inference requires a background worker" }
        }
    }
}

internal object FrameInterpolationNative {
    val status: FrameNativeLoader.Status get() = FrameNativeLoader.status()
    val available: Boolean get() = status == FrameNativeLoader.Status.READY

    /** Supply framework module APK + actual host codeCacheDir on a bounded background worker. */
    fun prepare(moduleApk: File, hostCodeCache: File): FrameNativeLoader.Status =
        FrameNativeLoader.prepare(moduleApk, hostCodeCache, FrameInterpolationNative::class.java)

    external fun create(backend: Int, gpuIndex: Int, threads: Int): LongArray
    external fun load(handle: Long, param: ByteBuffer, weights: ByteBuffer): Int
    external fun process(handle: Long, left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
        width: Int, height: Int, leftStride: Int, rightStride: Int, outputStride: Int, timestep: Float): Int
    external fun close(handle: Long)
}
