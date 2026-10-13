package com.bilibili.ship.theseus.united.player.mediaplay.network;

import android.content.Context;
import android.content.SharedPreferences;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import tv.danmaku.biliplayerv2.service.IToastService;

/** 免费流量推广与正常网络提醒共享服务；只有 f 是推广协程入口。 */
public final class PromoNetworkServiceFixture {
    public final Context context = null;
    public final IToastService toast = null;
    public final SharedPreferences preferences = null;
    public int promotionRequests;
    public int promotionShows;
    public int networkMessages;

    public Object f(ContinuationImpl continuation) {
        promotionRequests++;
        promotionShows++;
        return kotlin.Unit.INSTANCE;
    }

    public void b() { networkMessages++; }
    public void c(boolean metered) { networkMessages++; }
    public void d(int errorCode) { networkMessages++; }
    public void e(String network) { networkMessages++; }
}
