package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;

/** Resolves only the caller's actual codeCacheDir; never creates or substitutes a directory. */
final class CanonicalCacheRoot {
    private CanonicalCacheRoot() { }

    static final class Identity {
        final long device, inode;
        final int mode, uid;
        Identity(long device, long inode, int mode, int uid) {
            this.device = device;
            this.inode = inode;
            this.mode = mode;
            this.uid = uid;
        }
    }

    interface Access {
        File canonical(File path) throws IOException;
        Identity lstat(File path) throws IOException;
        int uid();
        int typeMask();
        int directoryType();
    }

    private static final class AndroidAccess implements Access {
        static final AndroidAccess INSTANCE = new AndroidAccess();
        public File canonical(File path) throws IOException { return path.getCanonicalFile(); }
        public Identity lstat(File path) throws IOException {
            try {
                StructStat stat = Os.lstat(path.getPath());
                return new Identity(stat.st_dev, stat.st_ino, stat.st_mode, stat.st_uid);
            } catch (ErrnoException failure) { throw new IOException("cache_stat_failed", failure); }
        }
        public int uid() { return Process.myUid(); }
        public int typeMask() { return OsConstants.S_IFMT; }
        public int directoryType() { return OsConstants.S_IFDIR; }
    }

    static File resolve(File actualHostCodeCache) throws IOException {
        return resolve(actualHostCodeCache, AndroidAccess.INSTANCE);
    }

    static File resolve(File raw, Access access) throws IOException {
        requireRootPath(raw);
        File canonical = access.canonical(raw);
        requireRootPath(canonical);
        if (!canonical.equals(access.canonical(canonical))) throw new IOException("cache_canonical_unstable");
        int uid = access.uid();
        Identity anchor = access.lstat(raw);
        requireOwnedDirectory(anchor, uid, access);
        Identity selected = access.lstat(canonical);
        requireOwnedDirectory(selected, uid, access);
        requireSame(anchor, selected);
        requireSame(anchor, access.lstat(raw));
        requireSame(anchor, access.lstat(canonical));
        if (!canonical.equals(access.canonical(raw)) || !canonical.equals(access.canonical(canonical)))
            throw new IOException("cache_alias_changed");
        return canonical;
    }

    private static void requireRootPath(File path) throws IOException {
        if (path == null || !path.isAbsolute() || !"code_cache".equals(path.getName()))
            throw new IOException("cache_path_invalid");
    }

    private static void requireOwnedDirectory(Identity identity, int uid, Access access) throws IOException {
        if (identity == null || (identity.mode & access.typeMask()) != access.directoryType()
                || identity.uid != uid || (identity.mode & 0002) != 0)
            throw new IOException("cache_not_owned_or_private");
    }

    private static void requireSame(Identity anchor, Identity actual) throws IOException {
        if (actual == null || anchor.device != actual.device || anchor.inode != actual.inode
                || anchor.mode != actual.mode || anchor.uid != actual.uid)
            throw new IOException("cache_root_identity_changed");
    }
}
