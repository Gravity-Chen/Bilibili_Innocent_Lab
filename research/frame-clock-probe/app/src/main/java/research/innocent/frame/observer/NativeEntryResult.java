package research.innocent.frame.observer;

final class NativeEntryResult {
    enum Stage { CONTEXT, CACHE_NORMALIZE, MODULE_INFO, APK_CANONICAL, INSTALL_ROOT, NATIVE_DIRECTORY,
        ABI, SPEC, APK_HASH, APK_LIBRARY_ENTRY, LIBRARY_HASH, DEPENDENCY, BEFORE_LOAD, LOAD, QUERY, AFTER_LOAD, COMPLETE }
    enum Status { NOT_EXECUTED, VALIDATION_REJECTED, LOAD_FAILED, QUERY_FAILED,
        ENTRY_V2_OBSERVED, ENTRY_OTHER_VERSION_OBSERVED, ENTRY_NULL_OBSERVED,
        ENTRY_METADATA_INCOMPLETE, ENTRY_NOT_OBSERVED }
    Stage stage = Stage.CONTEXT;
    Status status = Status.NOT_EXECUTED;
    String apkHash, libraryHash, abi, shadowHash, nothingHash;
    Integer sdkInitResult;
    boolean loaded, queried, sameLoader, moduleSelfValidated;
    long flags, version;
    boolean callbackRegistered;
    int hookAttempts, hostHooksInstalled;
    ProbeOutputDiagnostic.Kind failureKind = ProbeOutputDiagnostic.Kind.NONE;

    void adopt(long value) {
        flags = value & 0xffffffffL; version = value >>> 32;
        if ((flags & ~63L) != 0 || ((flags & 32) != 0 && (flags & 1) == 0)
                || ((flags & 32) == 0 && (flags & ~1L) != 0)
                || ((flags & 34) == 32 && version != 0)
                || ((flags & 24) != 0 && (flags & 38) != 38)
                || ((flags & 32) != 0 && ((flags & 4) != 0) != ((flags & 2) != 0 && version == 2)))
            throw new IllegalArgumentException();
        queried = true;
        status = (flags & 1) == 0 ? Status.ENTRY_NOT_OBSERVED : (flags & 32) == 0
                ? Status.ENTRY_METADATA_INCOMPLETE : (flags & 2) == 0 ? Status.ENTRY_NULL_OBSERVED
                : version == 2 ? Status.ENTRY_V2_OBSERVED : Status.ENTRY_OTHER_VERSION_OBSERVED;
    }
    private String observed(int bit) { return queried ? Boolean.toString((flags & bit) != 0) : "null"; }
    private static String text(String value) {
        if (value == null) return "null";
        if (!value.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException();
        return "\"" + value + "\"";
    }
    String fields() {
        return "\"status\":\"" + status + "\",\"stage\":\"" + stage + "\",\"failure_kind\":\"" + failureKind
                + "\",\"module_apk_sha256_observed\":" + text(apkHash) + ",\"library_sha256\":" + text(libraryHash)
                + ",\"abi\":" + text(abi) + ",\"same_classloader\":" + sameLoader + ",\"library_loaded\":" + loaded
                + ",\"shadowhook_sha256\":" + text(shadowHash) + ",\"shadowhook_nothing_sha256\":" + text(nothingHash)
                + ",\"sdk_init_result\":" + sdkInitResult + ",\"framework_native_api_calls\":0"
                + ",\"native_dependency_loader\":\"private-probe-dexloader\",\"installation_route\":\"bounded-worker\""
                + ",\"sdk_signal_and_linker_instrumentation_may_be_installed\":true"
                + ",\"module_self_metadata_matched\":" + moduleSelfValidated
                + ",\"query_completed\":" + queried + ",\"native_init_entered\":" + observed(1)
                + ",\"native_api_version\":" + (queried && (flags & 34) == 34 ? Long.toString(version) : "null")
                + ",\"entry_metadata_complete\":" + observed(32) + ",\"entry_pointer_present\":" + observed(2)
                + ",\"hook_pointer_present\":" + (queried && (flags & 36) == 36 ? observed(8) : "null")
                + ",\"unhook_pointer_present\":" + (queried && (flags & 36) == 36 ? observed(16) : "null")
                + ",\"native_callback_registered\":" + callbackRegistered + ",\"probe_native_hook_calls\":" + hookAttempts
                + ",\"probe_host_native_hooks_installed\":" + hostHooksInstalled + ",\"probe_native_unhook_calls\":0,"
                + "\"framework_load_listener_may_be_activated\":true,\"models_used\":false,"
                + "\"media_payload_observed\":false,\"clock_instrumentation\":" + (hostHooksInstalled != 0) + ",\"gpu_requested\":false";
    }
}
