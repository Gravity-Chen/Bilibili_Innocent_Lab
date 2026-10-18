package research.innocent.frame.observer;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Stage.*;

/** Research caller admission only: no create/chmod/fallback; finite identity snapshots, not a directory lock. */
final class ProbeRootNormalizer {
    private ProbeRootNormalizer() {}
    static final class Identity {
        final long device, inode;
        final int type, uid, mode;
        Identity(long device,long inode,int type,int uid,int mode) {
            this.device=device;this.inode=inode;this.type=type;this.uid=uid;this.mode=mode;
        }
    }
    /** Test port uses plain snapshots; the three-argument runtime entry always selects AndroidAccess. */
    interface Access {
        File canonical(File file) throws Exception;
        Identity lstat(File file) throws Exception;
        int uid(); int directoryType(); int symlinkType();
    }
    private static final class AndroidAccess implements Access {
        static final AndroidAccess INSTANCE = new AndroidAccess();
        public File canonical(File file) throws Exception { return file.getCanonicalFile(); }
        public Identity lstat(File file) throws Exception {
            StructStat stat=Os.lstat(file.getPath());
            return new Identity(stat.st_dev,stat.st_ino,stat.st_mode & OsConstants.S_IFMT,stat.st_uid,stat.st_mode);
        }
        public int uid() { return Process.myUid(); }
        public int directoryType() { return OsConstants.S_IFDIR; }
        public int symlinkType() { return OsConstants.S_IFLNK; }
    }
    static File normalize(File actualContextRoot,String expectedLeaf,ProbeOutputDiagnostic d) throws Exception {
        return normalize(actualContextRoot,expectedLeaf,AndroidAccess.INSTANCE,d);
    }
    static boolean same(Identity a,Identity b) {
        return a!=null && b!=null && a.device==b.device && a.inode==b.inode && a.type==b.type && a.uid==b.uid && a.mode==b.mode;
    }
    static boolean safeType(Identity value,int directoryType,int symlinkType) { return value!=null && value.type==directoryType && value.type!=symlinkType; }
    static boolean safeOwner(Identity value,int uid) { return value!=null && value.uid==uid; }
    static boolean safeMode(Identity value) { return value!=null && (value.mode & 0002)==0; }
    private static Identity safe(File file,Access access,int uid,boolean canonical,ProbeOutputDiagnostic d) throws Exception {
        d.at(canonical ? NORM_CANONICAL_LSTAT : NORM_RAW_LSTAT); Identity value=access.lstat(file);
        d.at(canonical ? NORM_CANONICAL_TYPE : NORM_RAW_TYPE);
        if(!safeType(value,access.directoryType(),access.symlinkType()))throw new IllegalStateException();
        d.at(canonical ? NORM_CANONICAL_OWNER : NORM_RAW_OWNER);
        if(!safeOwner(value,uid))throw new IllegalStateException();
        d.at(canonical ? NORM_CANONICAL_MODE : NORM_RAW_MODE);
        if(!safeMode(value))throw new IllegalStateException();
        return value;
    }
    static File normalize(File raw,String expectedLeaf,Access access,ProbeOutputDiagnostic d) throws Exception {
        if(d==null)throw new IllegalArgumentException();
        try {
            d.at(NORM_INPUT);
            if(raw==null || !raw.isAbsolute() || access==null || d.failed()
                    || !("cache".equals(expectedLeaf) || "code_cache".equals(expectedLeaf)))throw new IllegalArgumentException();
            d.at(NORM_RAW_LEAF); if(!expectedLeaf.equals(raw.getName()))throw new IllegalStateException();
            d.at(NORM_CANONICAL_GET); File canonical=access.canonical(raw);
            d.at(NORM_CANONICAL_FIXED);
            if(canonical==null || !canonical.isAbsolute() || !canonical.equals(canonical.getAbsoluteFile()) || !canonical.equals(access.canonical(canonical)))throw new IllegalStateException();
            d.at(NORM_CANONICAL_LEAF); if(!expectedLeaf.equals(canonical.getName()))throw new IllegalStateException();
            d.at(NORM_UID); int uid=access.uid();
            Identity anchor=safe(raw,access,uid,false,d), firstCanonical=safe(canonical,access,uid,true,d);
            d.at(NORM_INITIAL_MATCH);
            if(!same(anchor,firstCanonical)) { d.normalizedIdentity(false); throw new IllegalStateException(); }
            d.at(NORM_RAW_RECHECK); Identity rawAgain=access.lstat(raw);
            d.at(NORM_CANONICAL_RECHECK); Identity canonicalAgain=access.lstat(canonical);
            d.at(NORM_RECHECK_MATCH);
            if(!same(anchor,rawAgain) || !same(anchor,canonicalAgain) || !same(rawAgain,canonicalAgain)) {
                d.normalizedIdentity(false); throw new IllegalStateException();
            }
            d.at(NORM_CANONICAL_FINAL);
            if(!canonical.equals(access.canonical(raw)) || !canonical.equals(access.canonical(canonical))) {
                d.normalizedIdentity(false); throw new IllegalStateException();
            }
            d.normalizedIdentity(true); d.at(NORM_COMPLETE); return canonical;
        } catch(Throwable failure) { d.failure(failure); throw failure; }
    }
}
