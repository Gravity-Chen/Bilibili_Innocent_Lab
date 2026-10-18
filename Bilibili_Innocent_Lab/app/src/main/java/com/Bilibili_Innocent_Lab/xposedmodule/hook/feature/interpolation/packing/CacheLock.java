package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

final class CacheLock {
    private CacheLock() { }
    static final class Busy extends IOException { Busy() { super("cache_busy"); } }
    static FileLock claim(FileChannel channel) throws IOException {
        final FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException busy) { throw new Busy(); }
        if (lock == null) throw new Busy();
        return lock;
    }
}
