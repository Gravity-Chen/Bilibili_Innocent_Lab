package research.innocent.frame.observer;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the actual resolver and receiver hook; no Android Application, worker or JNI runs. */
public final class ProbeLifecycleSelfTest {
    private static int checks;
    private static void check(boolean valid, String message) { checks++; if (!valid) throw new AssertionError(message); }
    public static class Base { public void onCreate() {} }
    public static class Owner extends Base { @Override public void onCreate() {} }
    public static class Actual extends Owner {}
    public static class Child extends Actual {}
    public abstract static class AbstractApp extends Base {}
    public interface InterfaceApp { void onCreate(); }
    public static class StaticApp { public static void onCreate() {} }
    public static class WrongReturn { public int onCreate() { return 1; } }
    public static class ParameterOnly { public void onCreate(Object value) {} }
    private static class Initialization { static boolean occurred; }
    public static class NoInit extends Owner { static { Initialization.occurred = true; } }

    private static XposedInterface.Chain chain(AtomicInteger calls, Object result, Throwable failure, Object receiver, boolean badReceiver) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
            if (method.getName().equals("proceed")) { if(args!=null)throw new AssertionError("original arguments replaced"); calls.incrementAndGet(); if (failure != null) throw failure; return result; }
            if (method.getName().equals("getThisObject")) { if (badReceiver) throw new IllegalStateException(); return receiver; }
            throw new AssertionError("Unexpected chain access: " + method.getName());
        });
    }
    private static void rejected(Class<?> type, Class<?> base, boolean missing) throws Exception {
        try { ProbeLifecycle.resolve(type,base); throw new AssertionError("invalid target accepted"); }
        catch (NoSuchMethodException expected) { check(missing,"only missing method produces missing result"); }
        catch (IllegalArgumentException expected) { check(!missing,"invalid ABI rejected"); }
    }
    public static void main(String[] args) throws Throwable {
        Method resolved=ProbeLifecycle.resolve(Actual.class,Base.class);
        check(resolved.getDeclaringClass()==Owner.class && resolved.getReturnType()==void.class && resolved.getParameterCount()==0,"inherited declaration owner resolved, not subclass getDeclaredMethod");
        check(ProbeLifecycle.resolve(Base.class,Base.class).getDeclaringClass()==Base.class,"default base onCreate remains explicit");
        Class<?> uninitialized=Class.forName(NoInit.class.getName(),false,NoInit.class.getClassLoader());
        check(ProbeLifecycle.resolve(uninitialized,Base.class).getDeclaringClass()==Owner.class && !Initialization.occurred,"metadata resolution never initializes Application");
        rejected(null,Base.class,false); rejected(Actual.class,null,false); rejected(Object.class,Base.class,false);
        rejected(AbstractApp.class,Base.class,false); rejected(InterfaceApp.class,Object.class,false);
        rejected(StaticApp.class,Object.class,false); rejected(WrongReturn.class,Object.class,false);
        rejected(ParameterOnly.class,Object.class,true);
        for (boolean originalThrows : new boolean[]{false,true}) for (boolean sinkThrows : new boolean[]{false,true}) {
            Object result = new Object(); Actual app = new Actual(); Throwable failure = new IllegalArgumentException("not logged");
            AtomicInteger calls = new AtomicInteger(), observed = new AtomicInteger();
            ProbeLifecycle hook = new ProbeLifecycle(Actual.class,(application, threw) -> { check(application == app && threw == originalThrows && calls.get() == 1, "actual receiver only after original metadata identity"); observed.incrementAndGet(); if (sinkThrows) throw new IllegalStateException(); });
            try { Object returned = hook.intercept(chain(calls,result,originalThrows?failure:null,app,false)); check(!originalThrows && returned == result, "return identity"); }
            catch (Throwable actual) { check(originalThrows && actual == failure, "original exception identity"); }
            check(calls.get()==1 && observed.get()==1, "original and observation each once");
        }
        AtomicInteger calls = new AtomicInteger(), observed = new AtomicInteger(); Actual real = new Actual();
        ProbeLifecycle hook = new ProbeLifecycle(Actual.class,(app,threw)->{check(app==real,"matching exact receiver");observed.incrementAndGet();});
        for(Object wrong:new Object[]{null,new Object(),new Owner(),new Child()}) hook.intercept(chain(calls,null,null,wrong,false));
        hook.intercept(chain(calls,null,null,real,true));
        check(calls.get()==5 && observed.get()==0,"wrong/null/derived receiver and metadata failure never consume opportunity or repeat original");
        hook.intercept(chain(calls,null,null,real,false)); hook.intercept(chain(calls,null,null,new Actual(),false));
        check(calls.get()==7 && observed.get()==1,"only first exact Application is observed");
        AtomicInteger executions = new AtomicInteger(), launches = new AtomicInteger(); AtomicReference<Throwable> error = new AtomicReference<>();
        ProbeLifecycle concurrent = new ProbeLifecycle(Actual.class,(app,threw)-> launches.incrementAndGet()); CountDownLatch ready = new CountDownLatch(1); Thread[] threads = new Thread[16];
        for(int i=0;i<threads.length;i++) threads[i]=new Thread(()->{try{if(!ready.await(5,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");concurrent.intercept(chain(executions,null,null,new Actual(),false));}catch(Throwable failure){error.set(failure);}});
        for(Thread thread:threads)thread.start();ready.countDown();for(Thread thread:threads){thread.join(5000);check(!thread.isAlive(),"bounded concurrency");}
        check(error.get()==null && executions.get()==16 && launches.get()==1,"all originals run, one observation across concurrent lifecycle calls");
        check(NativeEntryModule.allowedProcess("tv.danmaku.bili"),"main allowed");
        check(NativeEntryModule.allowedProcess("tv.danmaku.bili:ijkservice"),"exact IJK process allowed");
        for(String name:new String[]{null,"","system_server","tv.danmaku.bili:pushservice","research.innocent.frame.observer"})check(!NativeEntryModule.allowedProcess(name),"other process rejected");
        System.out.println("ProbeLifecycleSelfTest PASS checks="+checks+" actual_receiver=true inherited_owner=true native_loaded=false");
    }
}
