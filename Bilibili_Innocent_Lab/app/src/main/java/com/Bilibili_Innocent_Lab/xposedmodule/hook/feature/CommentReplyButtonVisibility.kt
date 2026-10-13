package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewTreeObserver
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

internal fun uniqueCommentReplyField(
    fields: List<Field>,
    binding: Any,
    isReply: (Any) -> Boolean
): Field? {
    var selected: Field? = null
    var target: Any? = null
    for (field in fields) {
        val value = field.get(binding) ?: continue
        if (!isReply(value)) continue
        if (target != null && value !== target) return null
        selected = field
        target = value
    }
    return selected
}

internal class CommentReplyFieldAccess(
    private val fields: List<Field>,
    private val isReply: (Any) -> Boolean
) {
    private var selected: Field? = null
    private var currentBinding = WeakReference<Any>(null)

    fun read(binding: Any): Any? {
        selected?.takeIf { currentBinding.get() === binding }?.let { field ->
            val value = field.get(binding)
            if (value != null && isReply(value)) return value
            selected = null
        }
        selected = null
        currentBinding.clear()
        val field = uniqueCommentReplyField(fields, binding, isReply) ?: return null
        selected = field
        currentBinding = WeakReference(binding)
        return field.get(binding)
    }
}

/** 宿主的 post 布局动作会恢复按钮；只在布局/可见性变化时校正，不安排逐帧任务。 */
internal class CommentReplyButtonVisibility(private val environment: HookEnvironment) {
    private val guards = WeakHashMap<View, Guard>()

    fun hide(view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.ERROR)
            return
        }
        val guard = guards[view] ?: Guard(view, environment).also {
            guards[view] = it
            view.addOnAttachStateChangeListener(it)
        }
        guard.hide()
        if (view.isAttachedToWindow) guard.observe(view)
    }

    private class Guard(view: View, private val environment: HookEnvironment) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
        private val target = WeakReference(view)
        private var replyId = view.id
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var observed = false

        fun hide() {
            val view = target.get() ?: return
            // Comment2 在 XML 构造之后才分配 ID；其专用控件在首次挂载时锁定真实身份。
            if (replyId == View.NO_ID && view.id != View.NO_ID) replyId = view.id
            if (view.id != replyId) {
                stop()
                return
            }
            if (!observed) {
                observed = true
                environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
            }
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
            }
        }

        fun observe(view: View) {
            val current = view.viewTreeObserver
            if (observer.get() === current || !current.isAlive) return
            stop()
            current.addOnGlobalLayoutListener(this)
            observer = WeakReference(current)
        }

        private fun stop() {
            observer.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
            observer.clear()
        }

        override fun onViewAttachedToWindow(view: View) = HostThreadGuard.run("comment.reply_button.attach") {
            hide()
            observe(view)
        }

        override fun onViewDetachedFromWindow(view: View) = HostThreadGuard.run("comment.reply_button.detach") {
            stop()
        }

        override fun onGlobalLayout() = HostThreadGuard.run("comment.reply_button.layout") {
            val view = target.get()
            if (view == null || !view.isAttachedToWindow) stop() else hide()
        }
    }

    private companion object {
        const val CAPABILITY = "comments_reply_button_removed"
    }
}
