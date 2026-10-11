package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 非导出接收器只接收模块自己的不可变 PendingIntent，旧任务的按钮不会影响新任务。 */
class AgentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.data?.takeIf { it.scheme == "agent-task" }?.schemeSpecificPart ?: return
        if (!AgentController.owns(task)) return
        when (intent.action) {
            STOP -> AgentController.cancel(context)
            DISMISS -> dismissedTask = task
            TOGGLE_LOGS -> if (task == AgentController.currentTaskId() && AgentController.state.running)
                AgentSessionService.toggleNotificationLogs(task)
        }
    }
    companion object {
        internal const val STOP = "com.Bilibili_Innocent_Lab.xposedmodule.agent.STOP"
        internal const val DISMISS = "com.Bilibili_Innocent_Lab.xposedmodule.agent.DISMISS"
        internal const val TOGGLE_LOGS = "com.Bilibili_Innocent_Lab.xposedmodule.agent.TOGGLE_LOGS"
        @Volatile private var dismissedTask: String? = null
        internal fun dismissed(task: String): Boolean = dismissedTask == task
    }
}

/** 仅改变同一活动任务的通知展示；旧按钮、任务结束和新任务均不能继承查看模式。 */
internal class AgentNotificationLogMode {
    private var owner: String? = null
    private var logs = false

    fun synchronize(task: String?) {
        if (owner == task) return
        owner = task
        logs = false
    }

    fun toggle(task: String, current: String?, running: Boolean): Boolean {
        if (!running || task.isBlank() || task != owner || task != current) return false
        logs = !logs
        return true
    }

    fun showing(task: String): Boolean = owner == task && logs
}

/** 通知只选择当前任务的最近枚举事件；没有目标、身份凭据或原始响应的文本槽位。 */
internal object AgentNotificationLogContent {
    const val MAX_ROWS = 6
    const val MAX_CHARS = 2_000
    fun recent(snapshot: AgentLogSnapshot, task: String): List<AgentLogEvent> =
        snapshot.tasks.firstOrNull { it.id == task }?.events?.takeLast(MAX_ROWS).orEmpty()
}
