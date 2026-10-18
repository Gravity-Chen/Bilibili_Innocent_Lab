package research.innocent.frame.observer;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.ErrnoException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Only actual API102 SELF metadata; no host library lookup, alias substitution or fallback. */
final class NativeLibraryVerifier {
    private static final String SELF = "research.innocent.frame.observer";
    static final class Verified {
        final File source, library;
        final NativeLibrarySpec spec;
        final List<File> paths;
        final List<StructStat> identities;
        Verified(File source, File library, NativeLibrarySpec spec, List<File> paths, List<StructStat> identities) {
            this.source = source; this.library = library; this.spec = spec; this.paths = paths; this.identities = identities;
        }
        void recheck() throws Exception {
            for (int i = 0; i < paths.size(); i++) {
                File path = paths.get(i);
                requireCanonical(path);
                StructStat current = Os.lstat(path.getPath());
                same(identities.get(i), current);
                requireNoHostWrite(path, current);
            }
            checkElf(library, spec, identities.get(identities.size() - 1));
            checkedHash(library, identities.get(identities.size() - 1), spec.bytes, spec.sha256);
        }
    }
    private NativeLibraryVerifier() {}
    static boolean under(File root, File child) {
        return child.toPath().startsWith(root.toPath()) && !child.equals(root);
    }
    static boolean ownerPermitted(int uid, int sourceUid, int installUid, int moduleUid, int hostUid) {
        return uid != hostUid && (uid == sourceUid || uid == installUid || uid == moduleUid);
    }
    static Verified inspect(ApplicationInfo own, Class<?> module, NativeEntryResult result) throws Exception {
        return inspect(own, module, result, null);
    }
    static Verified inspect(ApplicationInfo own, Class<?> module, NativeEntryResult result, String dependency) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException();
        result.stage = NativeEntryResult.Stage.MODULE_INFO;
        if (own == null || !SELF.equals(own.packageName) || own.sourceDir == null || own.nativeLibraryDir == null
                || own.uid == Process.myUid()) throw new IllegalArgumentException();
        result.moduleSelfValidated = true;
        ClassLoader cl = module.getClassLoader();
        if (cl == null || cl != NativeEntryBridge.class.getClassLoader() || cl != NativeLibraryVerifier.class.getClassLoader()
                || cl != NativeLibrarySpec.class.getClassLoader() || cl != NativeLibrarySpecs.class.getClassLoader()
                || cl != NativeEntryResult.class.getClassLoader()
                || !"research.innocent.frame.observer.NativeEntryModule".equals(module.getName())
                || !"research.innocent.frame.observer.NativeEntryBridge".equals(NativeEntryBridge.class.getName()))
            throw new IllegalStateException();
        result.sameLoader = true;
        result.stage = NativeEntryResult.Stage.APK_CANONICAL;
        File source = new File(own.sourceDir); requireCanonical(source);
        StructStat sourceStat = Os.lstat(source.getPath());
        if (!OsConstants.S_ISREG(sourceStat.st_mode) || sourceStat.st_size <= 0 || sourceStat.st_size > NativeLibrarySpec.APK_LIMIT)
            throw new IllegalStateException();
        requireNoHostWrite(source, sourceStat);
        result.stage = NativeEntryResult.Stage.INSTALL_ROOT;
        File installRoot = source.getParentFile(); requireCanonical(installRoot);
        StructStat rootStat = Os.lstat(installRoot.getPath());
        if (!OsConstants.S_ISDIR(rootStat.st_mode)) throw new IllegalStateException();
        requireNoHostWrite(installRoot, rootStat);
        List<File> paths = new ArrayList<>(); List<StructStat> identities = new ArrayList<>();
        paths.add(source); identities.add(sourceStat); paths.add(installRoot); identities.add(rootStat);
        result.stage = NativeEntryResult.Stage.NATIVE_DIRECTORY;
        File nativeDirectory = new File(own.nativeLibraryDir); requireCanonical(nativeDirectory);
        if (!under(installRoot, nativeDirectory)) throw new IllegalStateException();
        File cursor = nativeDirectory;
        for (int depth = 0; !cursor.equals(installRoot); depth++) {
            if (depth >= 4) throw new IllegalStateException();
            requireCanonical(cursor);
            StructStat stat = Os.lstat(cursor.getPath());
            if (!OsConstants.S_ISDIR(stat.st_mode) || !ownerPermitted(stat.st_uid, sourceStat.st_uid, rootStat.st_uid, own.uid, Process.myUid()))
                throw new IllegalStateException();
            requireNoHostWrite(cursor, stat); paths.add(cursor); identities.add(stat); cursor = cursor.getParentFile();
        }
        result.stage = NativeEntryResult.Stage.ABI;
        String abi = runtimeAbi(); result.abi = abi;
        result.stage = NativeEntryResult.Stage.SPEC;
        NativeLibrarySpec spec = dependency == null ? NativeLibrarySpecs.forAbi(abi) : NativeLibrarySpecs.dependency(dependency, abi);
        File library = new File(nativeDirectory, spec.name); requireCanonical(library);
        if (!library.getParentFile().equals(nativeDirectory)) throw new IllegalStateException();
        StructStat libraryStat = Os.lstat(library.getPath());
        if (!OsConstants.S_ISREG(libraryStat.st_mode) || libraryStat.st_size != spec.bytes
                || !ownerPermitted(libraryStat.st_uid, sourceStat.st_uid, rootStat.st_uid, own.uid, Process.myUid()))
            throw new IllegalStateException();
        requireNoHostWrite(library, libraryStat);
        result.stage = NativeEntryResult.Stage.ABI;
        checkElf(library, spec, libraryStat);
        result.stage = NativeEntryResult.Stage.APK_HASH;
        result.apkHash = checkedHash(source, sourceStat, (int) sourceStat.st_size, null);
        result.stage = NativeEntryResult.Stage.APK_LIBRARY_ENTRY;
        String entry = "lib/" + abi + "/" + spec.name;
        try (ZipFile apk = new ZipFile(source)) {
            ZipEntry found = null; Enumeration<? extends ZipEntry> entries = apk.entries(); int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > 8192) throw new IllegalStateException();
                ZipEntry item = entries.nextElement();
                if (entry.equals(item.getName())) { if (found != null) throw new IllegalStateException(); found = item; }
            }
            if (found == null || found.isDirectory() || found.getSize() != spec.bytes) throw new IllegalStateException();
            try (InputStream input = apk.getInputStream(found)) {
                if (!spec.sha256.equals(hash(input, spec.bytes))) throw new IllegalStateException();
            }
        }
        same(sourceStat, Os.lstat(source.getPath()));
        result.stage = NativeEntryResult.Stage.LIBRARY_HASH;
        result.libraryHash = checkedHash(library, libraryStat, spec.bytes, spec.sha256);
        paths.add(library); identities.add(libraryStat);
        Verified verified = new Verified(source, library, spec, paths, identities);
        verified.recheck();
        return verified;
    }
    private static String runtimeAbi() {
        String expected = Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
        String[] advertised = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : advertised) if (expected.equals(abi)) return expected;
        throw new IllegalArgumentException();
    }
    private static void requireCanonical(File file) throws Exception {
        if (file == null || !file.isAbsolute() || !file.equals(file.getAbsoluteFile()) || !file.equals(file.getCanonicalFile()))
            throw new IllegalStateException();
    }
    static boolean requiresWritePermissionProof(int ownerUid, int mode, int hostUid) {
        if (ownerUid == hostUid || (mode & 0002) != 0) throw new IllegalStateException();
        return (mode & 0020) != 0;
    }
    private static void requireNoHostWrite(File path, StructStat stat) throws Exception {
        if (!requiresWritePermissionProof(stat.st_uid, stat.st_mode, Process.myUid())) return;
        // Android install roots may be system:system 0775. Group write alone does not grant host write.
        // access uses real credentials; require them to match the effective loader credentials.
        if (Os.getuid() != Process.myUid() || Os.getuid() != Os.geteuid() || Os.getgid() != Os.getegid())
            throw new IllegalStateException();
        try {
            if (Os.access(path.getPath(), OsConstants.W_OK)) throw new IllegalStateException();
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.EACCES) throw failure;
        }
        same(stat, Os.lstat(path.getPath()));
    }
    private static void same(StructStat a, StructStat b) {
        if (a.st_dev != b.st_dev || a.st_ino != b.st_ino || a.st_mode != b.st_mode || a.st_uid != b.st_uid
                || a.st_gid != b.st_gid || a.st_size != b.st_size || a.st_mtime != b.st_mtime || a.st_ctime != b.st_ctime)
            throw new IllegalStateException();
    }
    private static String checkedHash(File file, StructStat identity, int bytes, String expected) throws Exception {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            same(identity, Os.fstat(descriptor));
            String actual;
            try (InputStream input = new FileInputStream(descriptor)) { descriptor = null; actual = hash(input, bytes); }
            same(identity, Os.lstat(file.getPath()));
            if (expected != null && !expected.equals(actual)) throw new IllegalStateException();
            return actual;
        } finally { if (descriptor != null) Os.close(descriptor); }
    }
    private static void checkElf(File file, NativeLibrarySpec spec, StructStat identity) throws Exception {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            same(identity, Os.fstat(descriptor));
            byte[] header = new byte[20]; int total = 0;
            try (InputStream input = new FileInputStream(descriptor)) {
                descriptor = null;
                while (total < header.length) {
                    int n = input.read(header, total, header.length - total);
                    if (n < 0) throw new IllegalStateException();
                    if (n == 0) { int one = input.read(); if (one < 0) throw new IllegalStateException(); header[total++] = (byte) one; }
                    else total += n;
                }
            }
            same(identity, Os.lstat(file.getPath()));
            requireElfHeader(header, spec.abi);
        } finally { if (descriptor != null) Os.close(descriptor); }
    }
    static void requireElfHeader(byte[] header, String abi) {
        boolean wide = "arm64-v8a".equals(abi);
        if (header == null || header.length != 20 || !(wide || "armeabi-v7a".equals(abi))
                || header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != (wide ? 2 : 1) || header[5] != 1
                || ((header[18] & 255) | (header[19] & 255) << 8) != (wide ? 183 : 40)) throw new IllegalArgumentException();
    }
    static String hash(InputStream input, int bytes) throws Exception {
        if (bytes <= 0 || bytes > NativeLibrarySpec.APK_LIMIT) throw new IllegalArgumentException();
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; int total = 0;
        while (total <= bytes) {
            int n = input.read(buffer, 0, Math.min(buffer.length, bytes - total + 1));
            if (n < 0) break;
            if (n == 0) { int one = input.read(); if (one < 0) break; buffer[0] = (byte) one; n = 1; }
            if (n > bytes - total) throw new IllegalArgumentException();
            hash.update(buffer, 0, n); total += n;
        }
        if (total != bytes) throw new IllegalArgumentException();
        char[] hex = new char[64]; byte[] value = hash.digest(); String digits = "0123456789abcdef";
        for (int i = 0; i < 32; i++) { hex[i * 2] = digits.charAt((value[i] & 255) >>> 4); hex[i * 2 + 1] = digits.charAt(value[i] & 15); }
        return new String(hex);
    }
}
