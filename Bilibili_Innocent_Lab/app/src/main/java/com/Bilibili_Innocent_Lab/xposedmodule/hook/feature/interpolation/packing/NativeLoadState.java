package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.util.concurrent.locks.ReentrantLock;

/** One loaded content identity per module ClassLoader. A mapped library is never unloaded or replaced. */
final class NativeLoadState {
    interface Mapping { void load(Runnable mapped) throws Exception; }
    private final ReentrantLock lock = new ReentrantLock();
    private volatile FrameNativeLoader.Status status = FrameNativeLoader.Status.NOT_PREPARED;
    private String mappedHash;

    FrameNativeLoader.Status status() { return status; }

    FrameNativeLoader.Status prepare(String expectedHash, Mapping operation) {
        if (!lock.tryLock()) return FrameNativeLoader.Status.BUSY;
        try {
            if (mappedHash != null) return mappedHash.equals(expectedHash) ? status : FrameNativeLoader.Status.RESTART_REQUIRED;
            if (status == FrameNativeLoader.Status.PREPARING) return FrameNativeLoader.Status.BUSY;
            status = FrameNativeLoader.Status.PREPARING;
            try {
                operation.load(() -> mappedHash = expectedHash);
                status = mappedHash == null ? FrameNativeLoader.Status.FAILED : FrameNativeLoader.Status.READY;
            } catch (CacheLock.Busy busy) {
                status = FrameNativeLoader.Status.BUSY;
            } catch (Throwable failure) {
                // Retain mappedHash even if resource close fails after System.load. Never map another hash.
                status = FrameNativeLoader.Status.FAILED;
            }
            return status;
        } finally { lock.unlock(); }
    }
}
