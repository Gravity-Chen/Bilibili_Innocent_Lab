package research.innocent.frame.observer;

import dalvik.system.PathClassLoader;

/** Only the owned JNI bridge uses this private native namespace. */
final class ProbeNativeClassLoader extends PathClassLoader {
    static final String BRIDGE = "research.innocent.frame.observer.OwnedNativeBridge";
    ProbeNativeClassLoader(String apk, String libraries, ClassLoader parent) { super(apk, libraries, parent); }
    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!BRIDGE.equals(name)) return super.loadClass(name, resolve);
        synchronized (this) {
            Class<?> value = findLoadedClass(name);
            if (value == null) value = findClass(name);
            if (resolve) resolveClass(value);
            return value;
        }
    }
}
