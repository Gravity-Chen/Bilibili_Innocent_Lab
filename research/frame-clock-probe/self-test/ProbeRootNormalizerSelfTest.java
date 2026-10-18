package research.innocent.frame.observer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

/** Actual normalizer control flow with snapshot port; no Android Os/Context or native load. */
public final class ProbeRootNormalizerSelfTest {
    private static int checks;
    private static final int DIR=0x4000, LINK=0xa000, UID=70001;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static ProbeRootNormalizer.Identity id(long dev,long ino,int type,int uid,int mode) { return new ProbeRootNormalizer.Identity(dev,ino,type,uid,mode); }
    private static ProbeRootNormalizer.Identity good() { return id(31337,515151,DIR,UID,DIR|0700); }
    private static final class Fixture implements ProbeRootNormalizer.Access {
        final File raw; File canonical;
        final ProbeRootNormalizer.Identity[] snapshots={good(),good(),good(),good()};
        final List<File> statFiles=new ArrayList<>();
        int canonicalCalls,uidCalls,throwCanonical=-1,throwStat=-1,changeCanonical=-1;
        Fixture(File raw,File canonical) {this.raw=raw;this.canonical=canonical;}
        public File canonical(File file)throws IOException {
            int call=++canonicalCalls;if(call==throwCanonical)throw new IOException("private not logged");
            if(call==changeCanonical)return new File(canonical.getParentFile().getParentFile(),"changed/"+canonical.getName());
            return canonical;
        }
        public ProbeRootNormalizer.Identity lstat(File file)throws IOException {
            statFiles.add(file);int call=statFiles.size();if(call==throwStat)throw new IOException("private not logged");
            if(call>4)throw new AssertionError("unbounded stat");return snapshots[call-1];
        }
        public int uid(){uidCalls++;return UID;} public int directoryType(){return DIR;} public int symlinkType(){return LINK;}
    }
    private static void rejected(File raw,String leaf,Fixture f,String stage,String identity)throws Exception {
        ProbeOutputDiagnostic d=new ProbeOutputDiagnostic();boolean rejected=false;
        try{ProbeRootNormalizer.normalize(raw,leaf,f,d);}catch(IOException|IllegalStateException|IllegalArgumentException expected){rejected=true;}
        check(rejected&&d.failed()&&d.fields().startsWith("stage="+stage+" "),"closed rejection stage");
        check(d.normalizationFields().endsWith("identity="+identity),"failed validation never upgrades identity");
        check(f==null || f.statFiles.size()<=4 && f.canonicalCalls<=4,"all failed accesses bounded");
    }
    public static void main(String[] args)throws Exception {
        File root=new File(System.getProperty("user.dir")).getCanonicalFile();
        File raw=new File(root,"uncreated-raw/cache"),canonical=new File(root,"uncreated-canonical/cache");
        Fixture alias=new Fixture(raw,canonical);ProbeOutputDiagnostic d=new ProbeOutputDiagnostic();
        File returned=ProbeRootNormalizer.normalize(raw,"cache",alias,d);
        check(returned.equals(canonical)&&!returned.equals(raw)&&d.normalizationFields().endsWith("identity=1"),"same-directory alias returns only derived canonical");
        check(alias.statFiles.equals(List.of(raw,canonical,raw,canonical))&&alias.canonicalCalls==4&&alias.uidCalls==1,"exact bounded raw/canonical double checks");
        check(d.fields().startsWith("stage=NORM_COMPLETE type=NONE"),"complete does not claim prepare");
        Fixture already=new Fixture(canonical,canonical);ProbeOutputDiagnostic same=new ProbeOutputDiagnostic();
        check(ProbeRootNormalizer.normalize(canonical,"cache",already,same).equals(canonical),"already canonical accepted through same full checks");
        Set<String> fields=Arrays.stream(ProbeRootNormalizer.Identity.class.getDeclaredFields()).map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        check(fields.equals(Set.of("device","inode","type","uid","mode")),"directory size/time are not admission identity");
        ProbeRootNormalizer.Identity[] changed={id(31338,515151,DIR,UID,DIR|0700),id(31337,515152,DIR,UID,DIR|0700),id(31337,515151,LINK,UID,DIR|0700),id(31337,515151,DIR,UID+1,DIR|0700),id(31337,515151,DIR,UID,DIR|0770)};
        check(ProbeRootNormalizer.same(good(),good())&&!ProbeRootNormalizer.same(null,good()),"identity matcher requires real full tuple");
        for(ProbeRootNormalizer.Identity value:changed)check(!ProbeRootNormalizer.same(good(),value),"every identity field participates");
        for(File early:new File[]{null,new File("cache")}) {
            ProbeOutputDiagnostic earlyDiagnostic=new ProbeOutputDiagnostic();boolean denied=false;
            try{ProbeRootNormalizer.normalize(early,"cache",earlyDiagnostic);}catch(IllegalArgumentException expected){denied=true;}
            check(denied&&earlyDiagnostic.fields().startsWith("stage=NORM_INPUT"),"real Android entry rejects absent/relative before native Os");
        }
        Fixture wrongLeaf=new Fixture(new File(root,"wrong"),canonical);rejected(wrongLeaf.raw,"cache",wrongLeaf,"NORM_RAW_LEAF","-1");
        check(wrongLeaf.canonicalCalls==0&&wrongLeaf.statFiles.isEmpty(),"raw leaf rejected before resolver");
        rejected(raw,"other",new Fixture(raw,canonical),"NORM_INPUT","-1");
        rejected(raw,"cache",new Fixture(raw,new File(root,"canonical/wrong")),"NORM_CANONICAL_LEAF","-1");
        rejected(raw,"cache",new Fixture(raw,null),"NORM_CANONICAL_FIXED","-1");
        rejected(raw,"cache",new Fixture(raw,new File("cache")),"NORM_CANONICAL_FIXED","-1");
        for(int call:new int[]{1,2,3,4}) {
            Fixture f=new Fixture(raw,canonical);f.throwCanonical=call;
            rejected(raw,"cache",f,call==1?"NORM_CANONICAL_GET":call==2?"NORM_CANONICAL_FIXED":"NORM_CANONICAL_FINAL","-1");
        }
        String[] statStages={"NORM_RAW_LSTAT","NORM_CANONICAL_LSTAT","NORM_RAW_RECHECK","NORM_CANONICAL_RECHECK"};
        for(int call=1;call<=4;call++){Fixture f=new Fixture(raw,canonical);f.throwStat=call;rejected(raw,"cache",f,statStages[call-1],"-1");}
        ProbeRootNormalizer.Identity[] unsafe={id(1,2,LINK,UID,LINK|0700),id(1,2,0x8000,UID,0x8000|0600),id(1,2,DIR,UID+1,DIR|0700),id(1,2,DIR,UID,DIR|0702)};
        String[] unsafeSuffix={"TYPE","TYPE","OWNER","MODE"};
        for(int side=0;side<2;side++)for(int i=0;i<unsafe.length;i++) {
            Fixture f=new Fixture(raw,canonical);f.snapshots[side]=unsafe[i];
            rejected(raw,"cache",f,"NORM_"+(side==0?"RAW_":"CANONICAL_")+unsafeSuffix[i],"-1");
        }
        for(int field:new int[]{0,1,4}){Fixture f=new Fixture(raw,canonical);f.snapshots[1]=changed[field];rejected(raw,"cache",f,"NORM_INITIAL_MATCH","0");}
        for(int side:new int[]{2,3})for(ProbeRootNormalizer.Identity value:changed){Fixture f=new Fixture(raw,canonical);f.snapshots[side]=value;rejected(raw,"cache",f,"NORM_RECHECK_MATCH","0");}
        for(int call:new int[]{3,4}){Fixture f=new Fixture(raw,canonical);f.changeCanonical=call;rejected(raw,"cache",f,"NORM_CANONICAL_FINAL","0");}
        Fixture unstable=new Fixture(raw,canonical);unstable.changeCanonical=2;rejected(raw,"cache",unstable,"NORM_CANONICAL_FIXED","-1");
        ProbeOutputDiagnostic rawFacts=new ProbeOutputDiagnostic();rawFacts.fact(ProbeOutputDiagnostic.Fact.CANONICAL_EQUAL,false);
        String before=rawFacts.fields();ProbeOutputDiagnostic cacheNorm=new ProbeOutputDiagnostic(),codeNorm=new ProbeOutputDiagnostic();
        ProbeRootNormalizer.normalize(raw,"cache",new Fixture(raw,canonical),cacheNorm);
        File codeRaw=new File(root,"raw/code_cache"),codeCanonical=new File(root,"canonical/code_cache");Fixture codeBad=new Fixture(codeRaw,codeCanonical);codeBad.snapshots[0]=unsafe[2];
        try{ProbeRootNormalizer.normalize(codeRaw,"code_cache",codeBad,codeNorm);}catch(IllegalStateException expected){}
        check(rawFacts.fields().equals(before)&&cacheNorm.normalizationFields().endsWith("identity=1")&&codeNorm.failed(),"raw facts and both normalization outcomes remain independent");
        check(!cacheNorm.normalizationFields().contains("31337")&&!cacheNorm.normalizationFields().contains("515151")&&!cacheNorm.normalizationFields().contains("70001")&&!cacheNorm.normalizationFields().contains(root.getPath()),"projection omits raw identities and paths");
        System.out.println("ProbeRootNormalizerSelfTest PASS checks="+checks+" actual_control_flow=true access_fixture=true android_os_executed=false native_loaded=false");
    }
}
