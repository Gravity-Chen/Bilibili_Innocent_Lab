package research.innocent.frame.observer;

import dalvik.system.BaseDexClassLoader;

/** Loaded only through the verified probe APK and its read-only native directory. */
public final class OwnedNativeBridge {
    private OwnedNativeBridge() {}
    public static void loadLibraries(String own, String shadow, String nothing) {
        ClassLoader loader = OwnedNativeBridge.class.getClassLoader();
        if (!(loader instanceof BaseDexClassLoader)
                || !nothing.equals(((BaseDexClassLoader) loader).findLibrary("shadowhook_nothing"))
                || !shadow.equals(((BaseDexClassLoader) loader).findLibrary("shadowhook"))) throw new IllegalStateException();
        // DT_NEEDED resolves the pinned SDK in this namespace. Only our JNI_OnLoad runs;
        // the SDK is initialized through its C API, without registering Java SDK natives.
        System.load(own);
    }
    public static native long queryFlags();
    public static native int initialize(String expectedDependency);
    public static native boolean beginObservation();
    public static native boolean tryInstall();
    public static native void endObservation();
    public static native long[] statistics();
    public static native long[] drain();
}
