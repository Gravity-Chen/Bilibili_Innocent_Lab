package research.innocent.frame.observer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Actual compiled result/policy/hash logic only; no Android Context, System.load or JNI executes. */
public final class NativeEntrySelfTest {
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void reject(Checked r, String label) throws Exception {
        boolean bad = false; try { r.run(); } catch (IllegalArgumentException expected) { bad = true; } check(bad, label);
    }
    static NativeEntryResult decoded(long version, int flags) {
        NativeEntryResult r = new NativeEntryResult(); r.adopt((version << 32) | flags); return r;
    }
    public static void main(String[] args) throws Exception {
        NativeEntryResult unknown = new NativeEntryResult();
        check(unknown.fields().contains("\"native_init_entered\":null"), "unqueried is unknown");
        check(decoded(0, 0).status == NativeEntryResult.Status.ENTRY_NOT_OBSERVED, "loaded without marker is not success");
        check(decoded(0, 1).status == NativeEntryResult.Status.ENTRY_METADATA_INCOMPLETE, "one weak claim can be incomplete");
        check(decoded(0, 33).status == NativeEntryResult.Status.ENTRY_NULL_OBSERVED, "NULL entries observed safely");
        NativeEntryResult two = decoded(2, 63);
        check(two.status == NativeEntryResult.Status.ENTRY_V2_OBSERVED && two.fields().contains("\"hook_pointer_present\":true"), "API2 presence only");
        check(decoded(2, 39).fields().contains("\"hook_pointer_present\":false"), "API2 NULL function pointer is observed false");
        check(decoded(3, 35).status == NativeEntryResult.Status.ENTRY_OTHER_VERSION_OBSERVED
                && decoded(3, 35).fields().contains("\"hook_pointer_present\":null"), "other version never claims pointer inspection");
        check(decoded(0xffffffffL, 35).fields().contains("4294967295"), "uint32 version retained without signed truncation");
        reject(() -> decoded(2, 64), "unknown bits"); reject(() -> decoded(2, 32), "complete without entered");
        reject(() -> decoded(3, 63), "API2 bit/version contradiction"); reject(() -> decoded(2, 9), "pointer without metadata");
        reject(() -> decoded(0, 2), "metadata without publication"); reject(() -> decoded(2, 33), "NULL version contradiction");
        two.apkHash = "a".repeat(64); two.libraryHash = "b".repeat(64); two.abi = "arm64-v8a";
        String line = two.fields();
        check(line.getBytes(StandardCharsets.UTF_8).length < 2000 && line.contains("\"probe_native_hook_calls\":0")
                && line.contains("\"framework_load_listener_may_be_activated\":true") && !line.contains("noNativeHooks"), "bounded scoped projection");
        check(!line.contains("st_uid") && !line.contains("path") && !line.contains("0x") && !line.contains("Exception"), "no raw identities/pointers/exception");
        two.apkHash = "/private/secret"; reject(two::fields, "uncontrolled text rejected");
        check(NativeEntryModule.allowedProcess("tv.danmaku.bili"), "exact main accepted");
        for (String s : new String[]{null, "tv.danmaku.bili2", "research.innocent.frame.observer"})
            check(!NativeEntryModule.allowedProcess(s), "other process rejected");
        File root = new File("/installed/pkg");
        check(NativeLibraryVerifier.under(root, new File(root, "lib/arm64")), "component child accepted");
        check(!NativeLibraryVerifier.under(root, root) && !NativeLibraryVerifier.under(root, new File("/installed/pkg-other/lib")), "prefix/same root rejected");
        check(NativeLibraryVerifier.ownerPermitted(1000, 1000, 0, 2000, 3000), "actual source owner accepted");
        check(NativeLibraryVerifier.ownerPermitted(0, 1000, 0, 2000, 3000), "actual install owner accepted");
        check(!NativeLibraryVerifier.ownerPermitted(3000, 3000, 3000, 2000, 3000), "host owner rejected even if metadata aliases");
        check(!NativeLibraryVerifier.ownerPermitted(4000, 1000, 0, 2000, 3000), "unrelated owner rejected");
        check(!NativeLibraryVerifier.requiresWritePermissionProof(1000, 0755, 10350), "non-writable mode needs no group proof");
        check(NativeLibraryVerifier.requiresWritePermissionProof(1000, 0775, 10350), "group-write install directory needs actual OS proof");
        for (int[] value : new int[][]{{10350, 0755}, {1000, 0777}, {1000, 0757}}) {
            boolean denied = false;
            try { NativeLibraryVerifier.requiresWritePermissionProof(value[0], value[1], 10350); }
            catch (IllegalStateException expected) { denied = true; }
            check(denied, "host-owner or world-write remains rejected");
        }
        for (String abi : new String[]{"arm64-v8a", "armeabi-v7a"}) {
            byte[] header = new byte[20]; header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
            header[4] = (byte) (abi.equals("arm64-v8a") ? 2 : 1); header[5] = 1; header[18] = (byte) (abi.equals("arm64-v8a") ? 183 : 40);
            NativeLibraryVerifier.requireElfHeader(header, abi); checks++;
            final byte[] broken = header.clone(); broken[18] ^= 1; reject(() -> NativeLibraryVerifier.requireElfHeader(broken, abi), "ELF machine");
            final byte[] wrongClass = header.clone(); wrongClass[4] ^= 3;
            reject(() -> NativeLibraryVerifier.requireElfHeader(wrongClass, abi), "ELF bitness");
        }
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        check(NativeLibraryVerifier.hash(new ByteArrayInputStream(payload), 3).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "actual bounded hash");
        reject(() -> NativeLibraryVerifier.hash(new ByteArrayInputStream(payload), 2), "overrun");
        reject(() -> NativeLibraryVerifier.hash(new ByteArrayInputStream(payload), 4), "truncation");
        reject(() -> NativeLibraryVerifier.hash(new ByteArrayInputStream(payload), 0), "zero bound");
        ByteArrayInputStream zero = new ByteArrayInputStream(payload) {
            boolean z;
            @Override public synchronized int read(byte[] b, int off, int len) { z = !z; return z ? 0 : super.read(b, off, len); }
        };
        check(NativeLibraryVerifier.hash(zero, 3).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "zero-read still progresses");
        System.out.println("NATIVE_ENTRY_JVM PASS checks=" + checks + " native_loaded=false device=false");
    }
}
