package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.View
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** 挂在已识别的内容列表或页面根容器，不插入叠层、不修改 Fragment 可见性。 */
internal class HostBackgroundController(private val config: HostBackgroundConfig, private val onError: (Throwable) -> Unit,
    private val onApplied: () -> Unit = {},
    private val imageLoader: (Context, HostBackgroundConfig) -> Bitmap? = HostBackgroundImages::load) {
    private class Surface(val drawable: HostBackgroundDrawable)
    private val surfaces = WeakHashMap<ViewGroup, Surface>()
    private val loadingFooter = HostBackgroundLoadingFooter()
    private var image: Bitmap? = null
    private var requested = false
    private var applied = false
    val requiresNight: Boolean get() = config.preset.isCelestial

    fun prepareList(list: ViewGroup) = loadingFooter.apply(list)

    fun release(view: ViewGroup) {
        val state = surfaces.remove(view) ?: return
        if (view.background === state.drawable) view.background = null
    }

    fun apply(list: ViewGroup, night: Boolean) {
        if (!config.enabled) return
        // 动态的凝光卡片在列表后方的兄弟层，壁纸必须位于整页后面。
        val page = HostBackgroundPages.owner(list)
        val pageKind = page?.let { HostBackgroundPages.kind(it) }
        val wallpaperOwner = page?.let(HostBackgroundPages::sharedWallpaperOwner)
        if (wallpaperOwner != null) {
            release(list)
            apply(wallpaperOwner, night)
            return
        }
        if (page != null && page !== list && pageKind != HostBackgroundPagePolicy.Kind.FEED && pageKind != HostBackgroundPagePolicy.Kind.LIVE) {
            apply(page, night)
            return
        }
        // 收藏夹内的横向预览属于外层列表内容，不能各自重画一块从原点开始的壁纸。
        // 页面根容器已经确定，不能再越过它提升到承载整个详情页的外层 RecyclerView。
        var ancestor = if (page === list) null else list.parent as? View
        var pageList = list
        while (ancestor != null) {
            if (ancestor is ViewGroup && isRecycler(ancestor.javaClass)) pageList = ancestor
            ancestor = ancestor.parent as? View
        }
        if (pageList !== list) { apply(pageList, night); return }
        val state = surfaces.getOrPut(list) {
            Surface(HostBackgroundDrawable(config, night, image))
        }
        val surface = state.drawable
        // 所有壁纸固定在页面视口；列表滚动、回顶和分页不改变壁纸原点。
        if (surface.night != night) { surface.night = night; surface.invalidateSelf() }
        if (list.background !== surface) list.background = surface
        // 加载更多尾项有独立的换肤背景，绘制前透出同一条连续壁纸。
        loadingFooter.apply(list)
        if (!applied) { applied = true; onApplied() }
        if (config.preset != HostBackgroundPreset.CUSTOM || requested) return
        requested = true
        val app = list.context.applicationContext
        worker.execute {
            val loaded = imageLoader(app, config)
            // 首个列表可能已经退出；View.post 会等待它重新 attach，甚至永远丢失加载结果。
            // 控制器共用图片，完成通知必须独立于任何一页的窗口生命周期。
            main.post {
                if (loaded == null) {
                    onError(IllegalStateException("Custom host background unavailable; using aurora"))
                } else {
                    image = loaded
                    // 每个列表持有自己的 Drawable，共享只读位图，避免 Drawable callback 串页。
                    for ((view, old) in surfaces.toMap()) {
                        val next = HostBackgroundDrawable(config, old.drawable.night, loaded)
                        surfaces[view] = Surface(next)
                        view.background = next
                    }
                }
            }
        }
    }

    private val recyclerTypes = HashMap<Class<*>, Boolean>()
    private fun isRecycler(type: Class<*>): Boolean = recyclerTypes.getOrPut(type) {
        var current: Class<*>? = type
        while (current != null) {
            // ViewPager2 的 RecyclerView 只是分页容器，不能把壁纸提升到标签栏背后。
            if (current.name.startsWith("androidx.viewpager2.")) return@getOrPut false
            if (current.name == "androidx.recyclerview.widget.RecyclerView") return@getOrPut true
            current = current.superclass
        }
        false
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-background").apply { isDaemon = true } }
    }
}
