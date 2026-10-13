package com.bilibili.ship.theseus.united.player.mediaplay.network;

import android.content.Context;
import android.content.SharedPreferences;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import tv.danmaku.biliplayerv2.service.IToastService;

/** 独立适配测试：相同 ABI 的其它 owner 与同 owner 的歧义不得猜测。 */
public final class PopupAdapterShapes {
    public static final class Other {
        public Context context;
        public IToastService toast;
        public SharedPreferences preferences;
        public Object f(ContinuationImpl continuation) { return null; }
    }
    public static final class Ambiguous {
        public Context context;
        public IToastService toast;
        public SharedPreferences preferences;
        public Object first(ContinuationImpl continuation) { return null; }
        public Object second(ContinuationImpl continuation) { return null; }
    }
    public static final class AmbiguousAnchor extends ContinuationImpl {
        public Ambiguous this$0;
        public AmbiguousAnchor() { super(null); }
        @Override public Object invokeSuspend(Object result) { return null; }
    }
    public static final class Broken {
        public Context context;
        public SharedPreferences preferences;
        public Object f(ContinuationImpl continuation) { return null; }
    }
    public static final class Old {
        public Context context;
        public IToastService toast;
        public SharedPreferences preferences;
        public Object q(Continuation<?> continuation) { return null; }
        public Object r(Continuation<?> continuation) { return null; }
        public int counter() { return 0; }
        public boolean frequency() { return false; }
        public Object display(Object info, Continuation<?> continuation) { return null; }
    }
}
