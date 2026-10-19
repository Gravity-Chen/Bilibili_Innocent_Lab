package research.innocent.frame.observer;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Stage.*;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Fact.*;

/** Worker-only fixed output. Diagnostic stages retain all existing checks. */
final class ProbeLog implements AutoCloseable {
    static final long MAX_BYTES = 512 * 1024;
    private final File path;
    private final FileOutputStream stream;
    private final ProbeOutputDiagnostic diagnostic;
    static File inspectRoot(File actual, String expectedLeaf, ProbeOutputDiagnostic d, boolean enforce) throws Exception {
        d.at(ROOT_ABSOLUTE); d.fact(PRESENT, actual != null);
        if (actual == null) throw new IllegalArgumentException();
        File base = actual.getAbsoluteFile(); d.fact(EXPECTED_LEAF, expectedLeaf.equals(base.getName()));
        d.at(ROOT_CANONICAL); boolean same = base.equals(base.getCanonicalFile()); d.fact(CANONICAL_EQUAL, same);
        if (enforce && !same) throw new IllegalStateException();
        d.at(ROOT_NO_SYMLINK); boolean noLink = !Files.isSymbolicLink(base.toPath()); d.linkProbe(NO_SYMLINK, !noLink);
        if (enforce && !noLink) throw new IllegalStateException();
        d.at(ROOT_LSTAT); StructStat stat = Os.lstat(base.getPath());
        d.at(ROOT_NO_SYMLINK); d.fact(NO_SYMLINK, !OsConstants.S_ISLNK(stat.st_mode));
        d.at(ROOT_DIRECTORY); boolean directory = OsConstants.S_ISDIR(stat.st_mode); d.fact(DIRECTORY, directory);
        if (enforce && !directory) throw new IllegalStateException();
        d.at(ROOT_OWNER); boolean owned = stat.st_uid == Process.myUid(); d.fact(OWNED, owned);
        if (enforce && !owned) throw new IllegalStateException();
        d.at(ROOT_NOT_WORLD_WRITABLE); boolean privateMode = (stat.st_mode & 0002) == 0; d.fact(NOT_WORLD_WRITABLE, privateMode);
        if (enforce && !privateMode) throw new IllegalStateException();
        return base;
    }
    ProbeLog(File actualCache, ProbeOutputDiagnostic diagnostic, String process) throws Exception {
        if (!"main_exact".equals(process) && !"ijkservice_exact".equals(process)) throw new IllegalArgumentException();
        this.diagnostic = diagnostic;
        FileDescriptor descriptor = null; FileOutputStream opened = null;
        try {
            File base = inspectRoot(actualCache, "cache", diagnostic, true);
            File directory = new File(base, "innocent-shadowhook-master-probe09");
            diagnostic.at(DIR_MKDIR);
            try { Os.mkdir(directory.getPath(), 0700); }
            catch (ErrnoException existing) { if (existing.errno != OsConstants.EEXIST) throw existing; }
            diagnostic.at(DIR_LSTAT); StructStat dir = Os.lstat(directory.getPath());
            diagnostic.at(DIR_DIRECTORY); boolean isDir = OsConstants.S_ISDIR(dir.st_mode); diagnostic.fact(CHILD_DIRECTORY,isDir);
            if (!isDir) throw new IllegalStateException();
            diagnostic.at(DIR_OWNER); boolean owner = dir.st_uid == Process.myUid(); diagnostic.fact(CHILD_OWNED,owner);
            if (!owner) throw new IllegalStateException();
            diagnostic.at(DIR_MODE); boolean mode = (dir.st_mode & 0777) == 0700; diagnostic.fact(CHILD_MODE_0700,mode);
            if (!mode) throw new IllegalStateException();
            diagnostic.at(DIR_PARENT); boolean parent = directory.getCanonicalFile().getParentFile().equals(base); diagnostic.fact(CHILD_PARENT_EQUAL,parent);
            if (!parent) throw new IllegalStateException();
            path = new File(directory, "main_exact".equals(process) ? "result-main.jsonl" : "result-ijkservice.jsonl");
            diagnostic.at(FILE_OPEN);
            descriptor = Os.open(path.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_APPEND
                    | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
            diagnostic.at(FILE_STREAM); opened = new FileOutputStream(descriptor);
            validate(opened);
            diagnostic.at(FILE_BOUND);
            if (opened.getChannel().size() > MAX_BYTES - 4096) throw new IllegalStateException();
            stream = opened;
        } catch (Throwable failure) {
            diagnostic.failure(failure);
            if (opened != null) opened.close(); else if (descriptor != null) Os.close(descriptor);
            throw failure;
        }
    }
    private void validate(FileOutputStream output) throws Exception {
        diagnostic.at(FILE_FSTAT); StructStat fd = Os.fstat(output.getFD());
        diagnostic.at(FILE_LSTAT); StructStat named = Os.lstat(path.getPath());
        diagnostic.at(FILE_REGULAR); boolean regular = OsConstants.S_ISREG(fd.st_mode); diagnostic.fact(FILE_IS_REGULAR,regular);
        if (!regular) throw new IllegalStateException();
        diagnostic.at(FILE_OWNER); boolean owned = fd.st_uid == Process.myUid(); diagnostic.fact(FILE_OWNED,owned);
        if (!owned) throw new IllegalStateException();
        diagnostic.at(FILE_MODE); boolean mode = (fd.st_mode & 0777) == 0600; diagnostic.fact(FILE_MODE_0600,mode);
        if (!mode) throw new IllegalStateException();
        diagnostic.at(FILE_IDENTITY); boolean same = fd.st_dev == named.st_dev && fd.st_ino == named.st_ino
                && fd.st_mode == named.st_mode && fd.st_uid == named.st_uid; diagnostic.fact(FILE_IDENTITY_EQUAL,same);
        if (!same) throw new IllegalStateException();
        diagnostic.at(FILE_NO_SYMLINK); diagnostic.fact(FILE_IS_NOT_SYMLINK,!OsConstants.S_ISLNK(named.st_mode));
        boolean noLink = !Files.isSymbolicLink(path.toPath()); diagnostic.linkProbe(FILE_IS_NOT_SYMLINK,!noLink);
        if (!noLink) throw new IllegalStateException();
    }
    boolean append(String line) throws Exception {
        return append(line, 0);
    }
    boolean appendObservation(String line) throws Exception {
        return append(line, 4096);
    }
    private boolean append(String line, int reserve) throws Exception {
        try {
            diagnostic.at(APPEND_ENCODE); byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            diagnostic.at(APPEND_LINE_BOUND); if (bytes.length > 32768) { diagnostic.reject(); return false; }
            diagnostic.at(APPEND_LOCK);
            try (FileLock claimed = stream.getChannel().tryLock()) {
                if (claimed == null) { diagnostic.reject(); return false; }
                validate(stream);
                diagnostic.at(APPEND_FILE_BOUND); long size = stream.getChannel().size();
                if (size > MAX_BYTES - reserve || bytes.length > MAX_BYTES - reserve - size) { diagnostic.reject(); return false; }
                diagnostic.at(APPEND_WRITE); stream.write(bytes);
                diagnostic.at(APPEND_FLUSH); stream.flush();
                diagnostic.at(APPEND_FSYNC); stream.getFD().sync();
                diagnostic.at(APPEND_UNLOCK); return true;
            }
        } catch (Throwable failure) { diagnostic.failure(failure); throw failure; }
    }
    @Override public void close() throws Exception {
        diagnostic.at(LOG_CLOSE);
        try { stream.close(); } catch (Throwable failure) { diagnostic.failure(failure); throw failure; }
    }
}
