package research.innocent.frame.observer;

import android.system.ErrnoException;
import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Stage.*;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Fact.*;
import static research.innocent.frame.observer.ProbeOutputDiagnostic.Kind.*;

/** Actual formatter and early ProbeLog rejection; no Android Os or JNI operation is executed. */
public final class ProbeOutputDiagnosticSelfTest {
    private static int checks;
    private static void check(boolean valid,String message) { checks++; if(!valid)throw new AssertionError(message); }
    private static final class PrivateFailure extends Throwable {
        private static final long serialVersionUID = 1L;
        @Override public String getMessage() { throw new AssertionError("message must never be read"); }
        @Override public String toString() { throw new AssertionError("Throwable must never be formatted"); }
    }
    public static void main(String[] args) throws Exception {
        ProbeOutputDiagnostic initial = new ProbeOutputDiagnostic();
        check(!initial.failed() && initial.fields().startsWith("stage=NOT_ATTEMPTED type=NONE errno=-1"),"not attempted is distinct from failure");
        check(initial.fields().endsWith("facts="+String.join(",",java.util.Collections.nCopies(16,"-1"))),"all unavailable metadata remains unknown");
        File missing = new File(System.getProperty("user.dir"), "diagnostic-not-created-"+java.util.UUID.randomUUID());
        check(!missing.exists() && !java.nio.file.Files.isSymbolicLink(missing.toPath()),"actual JDK link probe returns false for unavailable path");
        ProbeOutputDiagnostic unavailable = new ProbeOutputDiagnostic();
        unavailable.linkProbe(NO_SYMLINK,java.nio.file.Files.isSymbolicLink(missing.toPath()));
        unavailable.at(ROOT_LSTAT); unavailable.failure(new IOException("not logged"));
        check(unavailable.fields().endsWith("facts="+String.join(",",java.util.Collections.nCopies(16,"-1"))),"indeterminate false link result plus lstat failure never becomes known true");
        ProbeOutputDiagnostic knownLink = new ProbeOutputDiagnostic(); knownLink.linkProbe(NO_SYMLINK,true);
        check(knownLink.fields().endsWith("facts=-1,-1,-1,0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1"),"positive link result records known false non-link fact");
        ProbeOutputDiagnostic fileUnknown = new ProbeOutputDiagnostic(); fileUnknown.linkProbe(FILE_IS_NOT_SYMLINK,false);
        check(fileUnknown.fields().endsWith("facts="+String.join(",",java.util.Collections.nCopies(16,"-1"))),"same unknown contract for file link result");
        check(ProbeOutputDiagnostic.classifyType(ErrnoException.class)==ERRNO && ErrnoException.class.getField("errno").getType()==int.class,"actual SDK errno type and field ABI");
        for(int value:new int[]{1,2,13,17,4095})check(ProbeOutputDiagnostic.boundedErrno(value)==value,"bounded numeric errno preserved");
        for(int value:new int[]{Integer.MIN_VALUE,-1,0,4096,Integer.MAX_VALUE})check(ProbeOutputDiagnostic.boundedErrno(value)==-1,"invalid errno is unavailable");
        Throwable[] errors={new SecurityException("private"),new IOException("private"),new NoSuchMethodError("private"),new NoClassDefFoundError("private"),new UnsatisfiedLinkError("private"),new LinkageError("private"),new IllegalArgumentException("private"),new IllegalStateException("private"),new NullPointerException("private"),new PrivateFailure(),null};
        ProbeOutputDiagnostic.Kind[] types={SECURITY,IO,NO_SUCH_METHOD,NO_CLASS_DEF,UNSATISFIED_LINK,LINKAGE,ARGUMENT,STATE,NULL,OTHER,REJECTED};
        Pattern closed=Pattern.compile("stage=[A-Z_]+ type=[A-Z_]+ errno=-?[0-9]{1,4} facts=(?:-1|0|1)(?:,(?:-1|0|1)){15}");
        for(int i=0;i<errors.length;i++) {
            ProbeOutputDiagnostic d=new ProbeOutputDiagnostic();d.at(FILE_OPEN);d.failure(errors[i]);
            check(d.failed() && d.fields().contains("type="+types[i]+" "),"closed throwable classification");
            check(closed.matcher(d.fields()).matches() && !d.fields().contains("private"),"no Throwable text/path/class leak");
        }
        ProbeOutputDiagnostic sticky=new ProbeOutputDiagnostic();sticky.at(ROOT_CANONICAL);sticky.fact(CANONICAL_EQUAL,false);sticky.failure(new IOException("hidden"));
        sticky.at(LOG_CLOSE);sticky.failure(new SecurityException("cleanup"));
        check(sticky.fields().startsWith("stage=ROOT_CANONICAL type=IO"),"cleanup cannot overwrite first failure stage or kind");
        ProbeOutputDiagnostic unlock=new ProbeOutputDiagnostic();unlock.at(APPEND_UNLOCK);unlock.failure(new IOException("not logged"));unlock.at(LOG_CLOSE);
        check(unlock.fields().startsWith("stage=APPEND_UNLOCK type=IO"),"lock release failure is distinct from a completed fsync");
        ProbeOutputDiagnostic rejected=new ProbeOutputDiagnostic();rejected.at(APPEND_LOCK);rejected.reject();rejected.at(LOG_CLOSE);
        check(rejected.fields().startsWith("stage=APPEND_LOCK type=REJECTED"),"BUSY/bound refusal remains explicit after close");
        ProbeOutputDiagnostic values=new ProbeOutputDiagnostic();
        for(ProbeOutputDiagnostic.Fact fact:ProbeOutputDiagnostic.Fact.values())values.fact(fact,fact.ordinal()%2==0);
        check(values.fields().endsWith("facts=1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0") && values.fields().length()<1024,"fixed ordered tri-state projection bounded");
        ProbeOutputDiagnostic absent=new ProbeOutputDiagnostic();
        try { new ProbeLog(null,absent,"main_exact"); throw new AssertionError("null accepted"); }
        catch(IllegalArgumentException expected) { check(absent.fields().startsWith("stage=ROOT_ABSOLUTE type=ARGUMENT"),"actual constructor captures earliest failure before Android Os"); }
        ProbeOutputDiagnostic alias=new ProbeOutputDiagnostic();File noncanonical=new File(new File(System.getProperty("user.dir")),"missing/../missing");
        try { new ProbeLog(noncanonical,alias,"main_exact"); throw new AssertionError("noncanonical accepted"); }
        catch(IllegalStateException expected) { check(alias.fields().startsWith("stage=ROOT_CANONICAL type=STATE"),"actual canonical check retained before mkdir/Os"); }
        System.out.println("ProbeOutputDiagnosticSelfTest PASS checks="+checks+" actual_formatter=true sdk_errno_type=true android_errno_instance=false android_os_executed=false native_loaded=false");
    }
}
