package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.*;

public final class NativeLoadStateTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    @Test public void readyRequiresAnActualMappingReceipt() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(NOT_PREPARED, state.status());
        assertEquals(FAILED, state.prepare(A, mapped -> { }));
        assertEquals(READY, state.prepare(A, Runnable::run));
    }
    @Test public void ordinaryFailureCanBeRetriedExplicitly() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(FAILED, state.prepare(A, mapped -> { throw new IOException(); }));
        assertEquals(READY, state.prepare(A, Runnable::run));
    }
    @Test public void cacheBusyIsNotPermanentlyLazyFailed() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(BUSY, state.prepare(A, mapped -> { throw new CacheLock.Busy(); }));
        assertEquals(READY, state.prepare(A, Runnable::run));
    }
    @Test public void mappedHashIsNeverReplacedOrLoadedTwice() {
        NativeLoadState state = new NativeLoadState(); AtomicInteger loads = new AtomicInteger();
        assertEquals(READY, state.prepare(A, mapped -> { loads.incrementAndGet(); mapped.run(); }));
        assertEquals(READY, state.prepare(A, mapped -> fail("duplicate mapping")));
        assertEquals(RESTART_REQUIRED, state.prepare(B, mapped -> fail("replacement mapping")));
        assertEquals(READY, state.status()); assertEquals(1, loads.get());
    }
    @Test public void closeFailureAfterMappingRetainsIdentityAndDoesNotReportReady() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(FAILED, state.prepare(A, mapped -> { mapped.run(); throw new IOException(); }));
        assertEquals(FAILED, state.status());
        assertEquals(RESTART_REQUIRED, state.prepare(B, mapped -> fail("mapped identity forgotten")));
        assertEquals(FAILED, state.prepare(A, mapped -> fail("uncertain mapping retried")));
    }
    @Test public void reentrantPreparationReturnsBusyWithoutSecondMapping() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(READY, state.prepare(A, mapped -> {
            assertEquals(BUSY, state.prepare(A, nested -> fail("nested mapping")));
            mapped.run();
        }));
    }
    @Test public void concurrentPreparationNeverWaitsOrOverwritesInFlightStatus() throws Exception {
        NativeLoadState state = new NativeLoadState(); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread worker = new Thread(() -> assertEquals(READY, state.prepare(A, mapped -> {
            entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); mapped.run();
        })));
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try { assertEquals(PREPARING, state.status()); assertEquals(BUSY, state.prepare(B, mapped -> fail("concurrent map"))); assertEquals(PREPARING, state.status()); }
        finally { finish.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertEquals(READY, state.status());
    }
    @Test public void nativeLinkFailureDoesNotEnableFallbackOrReady() {
        NativeLoadState state = new NativeLoadState();
        assertEquals(FAILED, state.prepare(A, mapped -> { throw new UnsatisfiedLinkError(); }));
        assertEquals(FAILED, state.status());
        assertEquals(READY, state.prepare(A, Runnable::run));
    }
}
