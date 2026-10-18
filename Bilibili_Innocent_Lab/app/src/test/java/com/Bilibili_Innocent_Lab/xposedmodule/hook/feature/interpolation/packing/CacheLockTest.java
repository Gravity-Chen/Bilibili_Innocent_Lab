package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CacheLockTest {
    @Test public void overlappingClaimsFailBusyAndReleasedLockCanBeReclaimed() throws Exception {
        Path file = Files.createTempFile("innocent-frame-lock-test-", ".lock");
        try (FileChannel first = FileChannel.open(file, StandardOpenOption.WRITE);
             FileChannel second = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock held = CacheLock.claim(first)) {
                assertTrue(held.isValid());
                try { CacheLock.claim(second); fail("overlapping claim accepted"); }
                catch (CacheLock.Busy expected) { assertEquals("cache_busy", expected.getMessage()); }
            }
            try (FileLock reclaimed = CacheLock.claim(second)) { assertTrue(reclaimed.isValid()); }
        } finally { Files.deleteIfExists(file); }
    }
}
