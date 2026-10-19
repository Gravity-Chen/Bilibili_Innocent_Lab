package research.innocent.frame.observer;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private bounded clock observations. No model, capture, Surface or output permission. */
public final class NativeEntryModule extends XposedModule {
    private static final String TARGET = "tv.danmaku.bili";
    private final AtomicBoolean registered = new AtomicBoolean(), launched = new AtomicBoolean();
    private volatile String process;
    private volatile boolean finished;
    private volatile XposedInterface.HookHandle lifecycle;
    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!param.isSystemServer()) process = processLabel(param.getProcessName());
        if (process != null) bootstrap("MODULE_LOADED");
    }
    static String processLabel(String name) {
        return TARGET.equals(name) ? "main_exact" : (TARGET + ":ijkservice").equals(name) ? "ijkservice_exact" : null;
    }
    static boolean allowedProcess(String name) { return processLabel(name) != null; }
    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (process == null || !TARGET.equals(param.getPackageName()) || !registered.compareAndSet(false, true)) return;
        try {
            ApplicationInfo host = param.getApplicationInfo();
            ClassLoader loader = param.getClassLoader();
            if (host == null || host.className == null || loader == null) { bootstrap("NO_APPLICATION_METADATA"); return; }
            Class<?> application = Class.forName(host.className, false, loader);
            Method entry = ProbeLifecycle.resolve(application, Application.class);
            XposedInterface.HookHandle handle = hook(entry).setId("research.master09.application")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new ProbeLifecycle(application, this::afterLifecycle));
            lifecycle = handle;
            bootstrap("HOOK_ARMED");
            if (finished) handle.unhook();
        } catch (Throwable ignored) { bootstrap("NO_LIFECYCLE_HOOK"); }
    }
    private void afterLifecycle(Object application, boolean originalThrew) {
        if (originalThrew) return;
        if (!launched.compareAndSet(false, true)) return;
        WeakReference<Object> reference = new WeakReference<>(application);
        Thread worker = new Thread(() -> runProbe(reference), "innocent-master09-once");
        worker.setDaemon(true); worker.start();
    }
    private void runProbe(WeakReference<Object> reference) {
        NativeEntryResult result = new NativeEntryResult();
        ProbeOutputDiagnostic normalizer = new ProbeOutputDiagnostic(), diagnostic = new ProbeOutputDiagnostic();
        boolean nativeLoaded = false;
        try {
            Object receiver = reference.get();
            if (!(receiver instanceof Application)) { bootstrap("NO_APPLICATION_CONTEXT"); return; }
            Context context = ((Application)receiver).getApplicationContext();
            if (context == null || !TARGET.equals(context.getPackageName())) return;
            PackageInfo host = context.getPackageManager().getPackageInfo(TARGET, 0);
            long version = Build.VERSION.SDK_INT >= 28 ? host.getLongVersionCode() : host.versionCode;
            if (version != 8902100L) { bootstrap("UNSUPPORTED_HOST_VERSION"); return; }
            File cache = ProbeRootNormalizer.normalize(context.getCacheDir(), "cache", normalizer);
            String label = process;
            receiver = null; context = null; reference.clear();
            try (ProbeLog output = new ProbeLog(cache, diagnostic, label)) {
                try {
                    ApplicationInfo own = getModuleApplicationInfo();
                    NativeEntryResult dependencyCheck = new NativeEntryResult();
                    NativeLibraryVerifier.Verified shadow = NativeLibraryVerifier.inspect(own, NativeEntryModule.class, dependencyCheck, "libshadowhook.so");
                    NativeLibraryVerifier.Verified nothing = NativeLibraryVerifier.inspect(own, NativeEntryModule.class, dependencyCheck, "libshadowhook_nothing.so");
                    NativeLibraryVerifier.Verified verified = NativeLibraryVerifier.inspect(own, NativeEntryModule.class, result);
                    NativeEntryBridge.run(verified, shadow, nothing, result); nativeLoaded = result.loaded;
                    long[] initial = NativeEntryBridge.statistics();
                    ClockTraceProjection.adoptStatistics(result, initial);
                    String nonce = UUID.randomUUID().toString();
                    if (!output.append("{\"schema\":7,\"type\":\"header\",\"version\":\"0.9-shadowhook-master\",\"process\":\"" + label
                            + "\",\"observation_id\":\"" + nonce + "\",\"worker_trigger\":\"after_resolved_onCreate\","
                            + "\"native_load_precedes_onCreate\":null,"
                            + "\"clock_snapshot_produced\":false,\"media_epoch_bound\":false,\"surface_modified\":false,"
                            + "\"opaque_tokens_are_address_equivalence_only\":true,\"unhook_policy\":\"process_lifetime_passthrough\","
                            + result.fields() + ",\"native_state\":" + ClockTraceProjection.statistics(initial) + "}")) return;
                    boolean collecting = NativeEntryBridge.beginObservation();
                    long until = SystemClock.elapsedRealtimeNanos() + 12_000_000_000L;
                    boolean outputBoundReached = false;
                    if (collecting) {
                        while (SystemClock.elapsedRealtimeNanos() < until) {
                            NativeEntryBridge.tryInstall();
                            long[] events = NativeEntryBridge.drain();
                            if (events != null && events.length != 0 && !output.appendObservation(ClockTraceProjection.events(events))) {
                                outputBoundReached = true; break;
                            }
                            Thread.sleep(200L);
                        }
                    }
                    NativeEntryBridge.endObservation();
                    long[] tail = NativeEntryBridge.drain();
                    if (tail != null && tail.length != 0 && !output.appendObservation(ClockTraceProjection.events(tail))) outputBoundReached = true;
                    long[] last = NativeEntryBridge.statistics();
                    ClockTraceProjection.adoptStatistics(result, last);
                    output.append("{\"schema\":7,\"type\":\"footer\",\"collection_requested\":" + collecting
                            + ",\"output_bound_reached\":" + outputBoundReached
                            + ",\"clock_snapshot_produced\":false,\"process_restart_required_for_unmapping\":true,"
                            + "\"native_state\":" + ClockTraceProjection.statistics(last) + "}");
                    bootstrap("WINDOW_COMPLETE");
                } catch (Throwable failure) {
                    result.failureKind = ProbeOutputDiagnostic.classify(failure);
                    result.status = NativeEntryResult.Status.VALIDATION_REJECTED;
                    output.append("{\"schema\":7,\"type\":\"failure\"," + result.fields() + "}");
                } finally {
                    if (result.loaded) try { NativeEntryBridge.endObservation(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { bootstrap("OUTPUT_OR_CONTEXT_UNAVAILABLE"); }
        finally {
            reference.clear(); finished = true;
            if (nativeLoaded) try { NativeEntryBridge.endObservation(); } catch (Throwable ignored) { }
            XposedInterface.HookHandle handle = lifecycle;
            if (handle != null) try { handle.unhook(); } catch (Throwable ignored) { }
        }
    }
    private void bootstrap(String value) {
        try { log(Log.INFO, "InnocentMaster09", "master09 status=" + value); } catch (Throwable ignored) { }
    }
}
