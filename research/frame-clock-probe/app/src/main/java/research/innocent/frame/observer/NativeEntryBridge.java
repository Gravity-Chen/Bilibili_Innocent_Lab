package research.innocent.frame.observer;

import java.util.concurrent.atomic.AtomicBoolean;

/** Parent orchestrator; the owned JNI bridge has its own verified native namespace. */
final class NativeEntryBridge {
    private static final AtomicBoolean attempted = new AtomicBoolean();
    private static java.lang.reflect.Method begin, install, end, state, events;
    private NativeEntryBridge() {}
    static void run(NativeLibraryVerifier.Verified verified, NativeLibraryVerifier.Verified shadow,
                    NativeLibraryVerifier.Verified nothing, NativeEntryResult result) throws Exception {
        if (!attempted.compareAndSet(false, true)) throw new IllegalStateException();
        result.stage = NativeEntryResult.Stage.BEFORE_LOAD;
        verified.recheck();
        shadow.recheck(); nothing.recheck();
        if (!shadow.library.getParentFile().equals(nothing.library.getParentFile())) throw new IllegalStateException();
        if (!verified.source.equals(shadow.source) || !shadow.source.equals(nothing.source)) throw new IllegalStateException();
        ClassLoader loader = new ProbeNativeClassLoader(verified.source.getPath(), shadow.library.getParent(),
                NativeEntryBridge.class.getClassLoader());
        Class<?> bridge = loader.loadClass(ProbeNativeClassLoader.BRIDGE);
        if (bridge.getClassLoader() != loader) throw new IllegalStateException();
        java.lang.reflect.Method load = bridge.getMethod("loadLibraries", String.class, String.class, String.class);
        java.lang.reflect.Method query = bridge.getMethod("queryFlags");
        java.lang.reflect.Method initialize = bridge.getMethod("initialize", String.class);
        begin = bridge.getMethod("beginObservation"); install = bridge.getMethod("tryInstall");
        end = bridge.getMethod("endObservation"); state = bridge.getMethod("statistics"); events = bridge.getMethod("drain");
        result.stage = NativeEntryResult.Stage.LOAD;
        try {
            load.invoke(null, verified.library.getPath(), shadow.library.getPath(), nothing.library.getPath());
            result.loaded = true;
        }
        catch (Throwable error) { result.status = NativeEntryResult.Status.LOAD_FAILED; throw error; }
        result.stage = NativeEntryResult.Stage.QUERY;
        try { result.adopt((Long) query.invoke(null)); }
        catch (Throwable error) { result.status = NativeEntryResult.Status.QUERY_FAILED; throw error; }
        result.stage = NativeEntryResult.Stage.AFTER_LOAD;
        verified.recheck();
        shadow.recheck(); nothing.recheck();
        result.shadowHash = shadow.spec.sha256; result.nothingHash = nothing.spec.sha256;
        if (result.status == NativeEntryResult.Status.ENTRY_V2_OBSERVED && (result.flags & 24) == 24) {
            result.sdkInitResult = (Integer) initialize.invoke(null, shadow.library.getPath());
            if (result.sdkInitResult != 0) throw new IllegalStateException();
        }
        result.stage = NativeEntryResult.Stage.COMPLETE;
    }
    static boolean beginObservation() throws Exception { return (Boolean) begin.invoke(null); }
    static boolean tryInstall() throws Exception { return (Boolean) install.invoke(null); }
    static void endObservation() throws Exception { end.invoke(null); }
    static long[] statistics() throws Exception { return (long[]) state.invoke(null); }
    static long[] drain() throws Exception { return (long[]) events.invoke(null); }
}
