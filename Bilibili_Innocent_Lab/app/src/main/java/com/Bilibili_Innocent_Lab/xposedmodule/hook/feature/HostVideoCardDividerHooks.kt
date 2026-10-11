package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Canvas
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 仅接入已确认的分隔线绘制入口；不删除 ItemDecoration，不改变布局偏移或卡片阴影。 */
internal object HostVideoCardDividerHooks {
    fun install(environment: HookEnvironment, id: String, replacesSeparators: (ViewGroup) -> Boolean): Int {
        val loader = environment.classLoader ?: return 0
        val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return 0
        val state = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State") ?: return 0
        val targets = listOf(
            // 历史、收藏详情以及使用宿主通用装饰器的视频列表。
            Triple("native", "tv.danmaku.bili.widget.recycler.DividerDecoration", "onDrawOver"),
            // Pegasus 的 onDraw 绘制双列阴影，只有 onDrawOver 绘制单列底线。
            Triple("pegasus", "com.bilibili.pegasus.widget.j", "onDrawOver"),
            // 热门的旧式 Feed 装饰器在卡片 margin 外绘制上下分隔线。
            Triple("feed", "yn3.b", "onDraw"),
            // 播放列表通用分隔器按配置选择前景或背景绘制。
            Triple("playlist_under", "gt3.b", "onDraw"),
            Triple("playlist_over", "gt3.b", "onDrawOver"),
            // 收藏列表的 onDrawOver 委托给 onDraw，拦截后者即可覆盖两条路径。
            Triple("playset", "com.bilibili.playset.decoration.FlexibleDividerDecoration", "onDraw"),
            Triple("androidx", "androidx.recyclerview.widget.DividerItemDecoration", "onDraw")
        )
        var installed = 0
        for ((key, className, method) in targets) {
            val type = KavaMemberLookup.classOrNull(loader, className) ?: continue
            val draw = KavaMemberLookup.methodOrNull(type, method, Canvas::class.java, recycler, state)
                ?.takeIf { it.returnType == Void.TYPE } ?: continue
            environment.registrar.exact("$id.$key.divider", draw.declaringClass, draw.name, *draw.parameterTypes) {
                before {
                    val list = argOrNull(1) as? ViewGroup ?: return@before
                    if (replacesSeparators(list)) result = null
                }
            }
            installed++
        }
        return installed
    }
}
