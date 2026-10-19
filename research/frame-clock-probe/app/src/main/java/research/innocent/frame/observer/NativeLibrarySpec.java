package research.innocent.frame.observer;

final class NativeLibrarySpec {
    static final String NAME = "libinnocent_shadowhook_master_probe.so";
    static final int LIBRARY_LIMIT = 262144, APK_LIMIT = 4 * 1024 * 1024;
    final String name, abi, sha256;
    final int bytes;
    NativeLibrarySpec(String abi, int bytes, String sha256) {
        this(NAME, abi, bytes, sha256);
    }
    NativeLibrarySpec(String name, String abi, int bytes, String sha256) {
        if (!("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) || bytes <= 0 || bytes > LIBRARY_LIMIT
                || sha256 == null || !sha256.matches("[0-9a-f]{64}") ||
                !(NAME.equals(name) || "libshadowhook.so".equals(name) || "libshadowhook_nothing.so".equals(name))) throw new IllegalArgumentException();
        this.name = name; this.abi = abi; this.bytes = bytes; this.sha256 = sha256;
    }
}
