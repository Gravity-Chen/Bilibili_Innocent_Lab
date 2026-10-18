package research.innocent.frame.observer;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single lifecycle observation; original arguments/return/exception stay untouched. */
final class ProbeLifecycle implements XposedInterface.Hooker {
    interface Sink { void after(Object application, boolean originalThrew); }
    private final Class<?> applicationType;
    private final Sink sink;
    private final AtomicBoolean observed = new AtomicBoolean();
    ProbeLifecycle(Class<?> applicationType, Sink sink) {
        if (applicationType == null || sink == null) throw new IllegalArgumentException();
        this.applicationType = applicationType; this.sink = sink;
    }
    static Method resolve(Class<?> applicationType, Class<?> requiredBase) throws NoSuchMethodException {
        if (applicationType == null || requiredBase == null || !requiredBase.isAssignableFrom(applicationType)
                || applicationType.isInterface() || Modifier.isAbstract(applicationType.getModifiers()))
            throw new IllegalArgumentException();
        Method method = applicationType.getMethod("onCreate");
        if (method.getReturnType() != void.class || method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())
                || !requiredBase.isAssignableFrom(method.getDeclaringClass())) throw new IllegalArgumentException();
        return method;
    }
    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = null; Throwable original = null;
        try { result = chain.proceed(); return result; }
        catch (Throwable failure) { original = failure; throw failure; }
        finally {
            try {
                Object application = chain.getThisObject();
                if (application != null && application.getClass() == applicationType
                        && observed.compareAndSet(false, true)) sink.after(application, original != null);
            }
            catch (Throwable ignored) { /* Observer failure never changes or repeats the host call. */ }
        }
    }
}
