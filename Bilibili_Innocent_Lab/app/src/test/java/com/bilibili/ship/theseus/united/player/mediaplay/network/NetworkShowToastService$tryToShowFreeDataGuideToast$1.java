package com.bilibili.ship.theseus.united.player.mediaplay.network;

/** 与宿主保留的协程名和 owner 捕获字段一致，不启动真实调度器。 */
public final class NetworkShowToastService$tryToShowFreeDataGuideToast$1 extends kotlin.coroutines.jvm.internal.ContinuationImpl {
    public final PromoNetworkServiceFixture this$0;
    public int label;

    public NetworkShowToastService$tryToShowFreeDataGuideToast$1(PromoNetworkServiceFixture owner) {
        super(null);
        this.this$0 = owner;
    }

    @Override protected Object invokeSuspend(Object result) { return this$0.f(this); }
}
