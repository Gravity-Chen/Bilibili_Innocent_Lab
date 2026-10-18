package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** 原生折叠栏的背景随实际位移合成，不再等高度阈值触发独立显隐动画。 */
internal class HostDetailToolbarTransition private constructor(
    collapsing: ViewGroup, toolbar: View, private val access: Access
) : AutoCloseable {
    private val collapsing = WeakReference(collapsing)
    private val toolbar = WeakReference(toolbar)

    fun apply() {
        val collapsing = collapsing.get() ?: return
        val toolbar = toolbar.get() ?: return
        val range = collapsing.height - toolbar.height
        if (range <= 0 || toolbar.height <= 0) return
        val offset = access.offset.getInt(collapsing)
        val progress = (-offset.toFloat() / range).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        // Material 在 offset 回调中启动阈值动画；绘制前取消它，避免松手后继续追赶或反向抢写。
        // 这里操作的是宿主 ClassLoader 的 Material 实例，不能转型为模块的同名组件。
        (access.animator.get(collapsing) as? ValueAnimator)?.takeIf { it.isStarted }?.cancel()
        access.alpha.invoke(collapsing, (255f * eased).roundToInt())
    }

    override fun close() {
        val collapsing = collapsing.get() ?: return
        (access.animator.get(collapsing) as? ValueAnimator)?.cancel()
        val trigger = access.trigger.invoke(collapsing) as Int
        val shown = collapsing.height + access.offset.getInt(collapsing) < trigger
        access.alpha.invoke(collapsing, if (shown) 255 else 0)
    }

    private class Access(val offset: Field, val animator: Field, val alpha: Method, val trigger: Method)

    companion object {
        fun create(root: ViewGroup): HostDetailToolbarTransition? {
            fun find(node: View, depth: Int): ViewGroup? {
                if (depth > 16 || node !is ViewGroup || HostBackgroundPages.protected(node)) return null
                if (HostViewResourceNames.name(node) == "collapsing_toolbar" &&
                    (node.parent as? View)?.let(HostViewResourceNames::name) == "appbar") return node
                for (index in 0 until node.childCount) find(node.getChildAt(index), depth + 1)?.let { return it }
                return null
            }
            val collapsing = find(root, 0) ?: return null
            val toolbar = (0 until collapsing.childCount).map(collapsing::getChildAt)
                .singleOrNull { HostViewResourceNames.name(it) == "nav_top_bar" } ?: return null
            val type = generateSequence(collapsing.javaClass as Class<*>?) { it.superclass }
                .firstOrNull { it.name == "com.google.android.material.appbar.CollapsingToolbarLayout" } ?: return null
            // 仅接入已核对的 Material 成员；宿主接口不兼容时完整保留原生行为。
            val offset = Lookup.fieldOrNull(type, "currentOffset")?.takeIf { it.type == Int::class.javaPrimitiveType } ?: return null
            val animator = Lookup.fieldOrNull(type, "scrimAnimator")?.takeIf { it.type == ValueAnimator::class.java } ?: return null
            val alpha = Lookup.methodOrNull(type, "setScrimAlpha", Int::class.javaPrimitiveType!!) ?: return null
            val trigger = Lookup.methodOrNull(type, "getScrimVisibleHeightTrigger") ?: return null
            return HostDetailToolbarTransition(collapsing, toolbar, Access(offset, animator, alpha, trigger))
        }
    }
}
