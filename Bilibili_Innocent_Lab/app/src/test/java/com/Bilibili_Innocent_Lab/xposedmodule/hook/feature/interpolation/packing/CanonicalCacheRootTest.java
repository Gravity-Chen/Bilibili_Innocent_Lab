package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.io.File;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CanonicalCacheRootTest {
    private static final int UID = 12345, DIRECTORY = 0040000, SYMLINK = 0120000;
    private static final File BASE = new File(System.getProperty("java.io.tmpdir"), "innocent-cache-root-test");
    private static final File RAW = new File(BASE, "alias/code_cache").getAbsoluteFile();
    private static final File CANONICAL = new File(BASE, "actual/code_cache").getAbsoluteFile();
    private static CanonicalCacheRoot.Identity identity(long device, long inode, int mode, int uid) {
        return new CanonicalCacheRoot.Identity(device, inode, mode, uid);
    }
    private static final CanonicalCacheRoot.Identity OWNED = identity(7, 13, DIRECTORY | 0750, UID);

    private static final class Filesystem implements CanonicalCacheRoot.Access {
        final CanonicalCacheRoot.Identity[] snapshots = {OWNED, OWNED, OWNED, OWNED};
        final File[] paths = {CANONICAL, CANONICAL, CANONICAL, CANONICAL};
        int statCalls, canonicalCalls, failStat = -1, failCanonical = -1;
        public File canonical(File file) throws IOException {
            int call = ++canonicalCalls;
            if (call == failCanonical) throw new IOException("unavailable");
            assertEquals(call == 1 || call == 3 ? RAW : CANONICAL, file);
            return paths[call - 1];
        }
        public CanonicalCacheRoot.Identity lstat(File file) throws IOException {
            int call = ++statCalls;
            if (call == failStat) throw new IOException("unavailable");
            assertEquals(call == 1 || call == 3 ? RAW : CANONICAL, file);
            return snapshots[call - 1];
        }
        public int uid() { return UID; }
        public int typeMask() { return 0170000; }
        public int directoryType() { return DIRECTORY; }
    }

    @Test public void actualContextAliasResolvesOnlyToTheSameOwnedDirectory() throws Exception {
        Filesystem filesystem = new Filesystem();
        assertEquals(CANONICAL, CanonicalCacheRoot.resolve(RAW, filesystem));
        assertEquals(4, filesystem.statCalls);
        assertEquals(4, filesystem.canonicalCalls);
    }

    @Test public void anAlreadyCanonicalContextDirectoryAlsoPasses() throws Exception {
        CanonicalCacheRoot.Access filesystem = new CanonicalCacheRoot.Access() {
            public File canonical(File path) { assertEquals(CANONICAL, path); return path; }
            public CanonicalCacheRoot.Identity lstat(File path) { assertEquals(CANONICAL, path); return OWNED; }
            public int uid() { return UID; }
            public int typeMask() { return 0170000; }
            public int directoryType() { return DIRECTORY; }
        };
        assertEquals(CANONICAL, CanonicalCacheRoot.resolve(CANONICAL, filesystem));
    }

    @Test public void invalidCallerPathsNeverReachFilesystemAccess() {
        for (File path : new File[]{null, new File("code_cache"), new File(BASE, "cache").getAbsoluteFile()}) {
            Filesystem filesystem = new Filesystem();
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(path, filesystem));
            assertEquals(0, filesystem.statCalls);
            assertEquals(0, filesystem.canonicalCalls);
        }
    }

    @Test public void anInvalidCanonicalDestinationIsNotSubstituted() {
        for (File destination : new File[]{null, new File("code_cache"), new File(BASE, "cache").getAbsoluteFile()}) {
            Filesystem filesystem = new Filesystem(); filesystem.paths[0] = destination;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(0, filesystem.statCalls);
        }
    }

    @Test public void eitherRootMustBeADirectoryOwnedByTheProcessWithoutWorldWrite() {
        CanonicalCacheRoot.Identity[] unsafe = {
                identity(7, 13, SYMLINK | 0750, UID), identity(7, 13, 0100000 | 0750, UID),
                identity(7, 13, DIRECTORY | 0750, UID + 1), identity(7, 13, DIRECTORY | 0777, UID)
        };
        for (int position = 0; position < 2; position++) for (CanonicalCacheRoot.Identity identity : unsafe) {
            Filesystem filesystem = new Filesystem(); filesystem.snapshots[position] = identity;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test public void anotherOwnedDirectoryCannotReplaceTheCanonicalRoot() {
        for (CanonicalCacheRoot.Identity other : new CanonicalCacheRoot.Identity[]{
                identity(8, 13, OWNED.mode, UID), identity(7, 14, OWNED.mode, UID),
                identity(7, 13, DIRECTORY | 0700, UID)}) {
            Filesystem filesystem = new Filesystem(); filesystem.snapshots[1] = other;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test public void bothSecondSnapshotsAreBoundToTheOriginalIdentity() {
        for (int position = 2; position < 4; position++) for (CanonicalCacheRoot.Identity changed :
                new CanonicalCacheRoot.Identity[]{identity(7, 14, OWNED.mode, UID),
                        identity(7, 13, OWNED.mode, UID + 1), identity(7, 13, DIRECTORY | 0700, UID),
                        identity(7, 13, SYMLINK | 0750, UID)}) {
            Filesystem filesystem = new Filesystem(); filesystem.snapshots[position] = changed;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test public void rawCanonicalChangesDoNotTriggerFallback() {
        Filesystem filesystem = new Filesystem(); filesystem.paths[2] = new File(BASE, "other/code_cache");
        assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
    }

    @Test public void theCanonicalDestinationMustStayCanonical() {
        for (int position : new int[]{1, 3}) {
            Filesystem filesystem = new Filesystem(); filesystem.paths[position] = RAW;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test public void unavailableCanonicalizationCannotAuthorizeAnyRoot() {
        for (int call = 1; call <= 4; call++) {
            Filesystem filesystem = new Filesystem(); filesystem.failCanonical = call;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(call, filesystem.canonicalCalls);
        }
    }

    @Test public void unavailableStatsCannotAuthorizeAnyRoot() {
        for (int call = 1; call <= 4; call++) {
            Filesystem filesystem = new Filesystem(); filesystem.failStat = call;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(call, filesystem.statCalls);
        }
    }

    @Test public void missingSnapshotsCannotStandForOwnedDirectories() {
        for (int position = 0; position < 4; position++) {
            Filesystem filesystem = new Filesystem(); filesystem.snapshots[position] = null;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }
}
