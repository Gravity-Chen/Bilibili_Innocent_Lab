package research.innocent.frame.observer;

import android.system.ErrnoException;
import java.io.IOException;
import java.util.Arrays;

/** Worker-only closed projection. Never formats Throwable or filesystem identities. */
final class ProbeOutputDiagnostic {
    enum Stage { NOT_ATTEMPTED, CACHE_GET, CODE_CACHE_GET, ROOT_ABSOLUTE, ROOT_CANONICAL,
        ROOT_NO_SYMLINK, ROOT_LSTAT, ROOT_DIRECTORY, ROOT_OWNER, ROOT_NOT_WORLD_WRITABLE,
        DIR_MKDIR, DIR_LSTAT, DIR_DIRECTORY, DIR_OWNER, DIR_MODE, DIR_PARENT,
        FILE_OPEN, FILE_STREAM, FILE_FSTAT, FILE_LSTAT, FILE_REGULAR, FILE_OWNER, FILE_MODE,
        FILE_IDENTITY, FILE_NO_SYMLINK, FILE_BOUND, PREPARE, APPEND_ENCODE, APPEND_LINE_BOUND,
        APPEND_LOCK, APPEND_FILE_BOUND, APPEND_WRITE, APPEND_FLUSH, APPEND_FSYNC, APPEND_UNLOCK, LOG_CLOSE, COMPLETE,
        NORM_INPUT, NORM_RAW_LEAF, NORM_CANONICAL_GET, NORM_CANONICAL_FIXED, NORM_CANONICAL_LEAF, NORM_UID,
        NORM_RAW_LSTAT, NORM_RAW_TYPE, NORM_RAW_OWNER, NORM_RAW_MODE,
        NORM_CANONICAL_LSTAT, NORM_CANONICAL_TYPE, NORM_CANONICAL_OWNER, NORM_CANONICAL_MODE,
        NORM_INITIAL_MATCH, NORM_RAW_RECHECK, NORM_CANONICAL_RECHECK, NORM_RECHECK_MATCH,
        NORM_CANONICAL_FINAL, NORM_COMPLETE }
    enum Fact { PRESENT, EXPECTED_LEAF, CANONICAL_EQUAL, NO_SYMLINK, DIRECTORY, OWNED, NOT_WORLD_WRITABLE,
        CHILD_DIRECTORY, CHILD_OWNED, CHILD_MODE_0700, CHILD_PARENT_EQUAL,
        FILE_IS_REGULAR, FILE_OWNED, FILE_MODE_0600, FILE_IDENTITY_EQUAL, FILE_IS_NOT_SYMLINK }
    enum Kind { NONE, REJECTED, ERRNO, SECURITY, IO, NO_SUCH_METHOD, NO_CLASS_DEF,
        UNSATISFIED_LINK, LINKAGE, ARGUMENT, STATE, NULL, OTHER }
    private Stage current = Stage.NOT_ATTEMPTED, firstStage;
    private Kind kind = Kind.NONE;
    private int errno = -1;
    private int normalizedIdentity = -1;
    private final int[] facts = new int[Fact.values().length];
    ProbeOutputDiagnostic() { Arrays.fill(facts, -1); }
    void at(Stage stage) { if (stage == null) throw new IllegalArgumentException(); current = stage; }
    void fact(Fact fact, boolean value) { facts[fact.ordinal()] = value ? 1 : 0; }
    /** Files.isSymbolicLink(false) includes indeterminate I/O; only true supplies a fact. */
    void linkProbe(Fact fact, boolean isLink) { if (isLink) fact(fact, false); }
    void reject() { capture(Kind.REJECTED, -1); }
    void failure(Throwable failure) {
        if (failure instanceof ErrnoException error) capture(Kind.ERRNO, boundedErrno(error.errno));
        else capture(classify(failure), -1);
    }
    static int boundedErrno(int value) { return value > 0 && value <= 4095 ? value : -1; }
    static Kind classify(Throwable failure) {
        return classifyType(failure == null ? null : failure.getClass());
    }
    static Kind classifyType(Class<?> type) {
        if (type == null) return Kind.REJECTED;
        if (ErrnoException.class.isAssignableFrom(type)) return Kind.ERRNO;
        if (SecurityException.class.isAssignableFrom(type)) return Kind.SECURITY;
        if (IOException.class.isAssignableFrom(type)) return Kind.IO;
        if (NoSuchMethodError.class.isAssignableFrom(type)) return Kind.NO_SUCH_METHOD;
        if (NoClassDefFoundError.class.isAssignableFrom(type)) return Kind.NO_CLASS_DEF;
        if (UnsatisfiedLinkError.class.isAssignableFrom(type)) return Kind.UNSATISFIED_LINK;
        if (LinkageError.class.isAssignableFrom(type)) return Kind.LINKAGE;
        if (IllegalArgumentException.class.isAssignableFrom(type)) return Kind.ARGUMENT;
        if (IllegalStateException.class.isAssignableFrom(type)) return Kind.STATE;
        if (NullPointerException.class.isAssignableFrom(type)) return Kind.NULL;
        return Kind.OTHER;
    }
    private void capture(Kind next, int number) {
        if (firstStage != null) return;
        firstStage = current; kind = next; errno = number;
    }
    boolean failed() { return firstStage != null; }
    void normalizedIdentity(boolean same) { normalizedIdentity = same ? 1 : 0; }
    String normalizationFields() { return fields() + " identity=" + normalizedIdentity; }
    String fields() {
        StringBuilder out = new StringBuilder(640).append("stage=").append(firstStage == null ? current : firstStage)
                .append(" type=").append(kind).append(" errno=").append(errno).append(" facts=");
        for (int i = 0; i < facts.length; i++) { if (i != 0) out.append(','); out.append(facts[i]); }
        return out.toString();
    }
}
