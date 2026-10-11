package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskState
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentNotificationReceiver
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentExecutionLogStore
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentNotificationLogContent
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogTool

/** 使用标准系统通知；新版是否提升为状态栏 chip/岛由系统和用户通知设置决定。 */
internal object AgentTaskNotification {
    const val ID = 7301
    const val CHANNEL = "agent_task"
    const val OPEN_LOGS = "com.Bilibili_Innocent_Lab.xposedmodule.agent.OPEN_LOGS"
    fun create(context: Context, task: String, state: AgentTaskState, logMode: Boolean = false): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.agent_title), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        fun control(actionName: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, AgentNotificationReceiver::class.java).setAction(actionName).setData(Uri.parse("agent-task:$task")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val logs = control(AgentNotificationReceiver.TOGGLE_LOGS, 3)
        val currentTip = AgentStatusText.tip(context, state)
        val recent = if (state.showRecentOperation && state.lastOperation.isNotBlank()) context.getString(
            if (state.lastOperationSucceeded == true) R.string.agent_recent_completed else R.string.agent_recent_failed,
            AgentStatusText.tip(context, state.copy(phase = state.lastOperation))) else null
        val tip = recent?.let { "$it · $currentTip" } ?: currentTip
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_science)
            .setContentTitle(context.getString(R.string.agent_notification_title)).setContentText(tip)
            .setContentIntent(logs).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setDeleteIntent(control(AgentNotificationReceiver.DISMISS, 2))
            .addAction(Notification.Action.Builder(null, context.getString(if (logMode) R.string.agent_notification_show_status else R.string.agent_logs_title), logs).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.agent_stop), control(AgentNotificationReceiver.STOP, 1)).build())
        if (logMode) builder.setStyle(Notification.BigTextStyle().setBigContentTitle(context.getString(R.string.agent_logs_title))
            .bigText(logText(context, task)))
        if (Build.VERSION.SDK_INT >= 36) AgentPromotedNotification.configure(builder, manager, recent ?: currentTip,
            AgentPreferences.islandAllowed(context) && !AgentNotificationReceiver.dismissed(task), logMode)
        return builder.build()
    }
    private fun logText(context: Context, task: String): String {
        val events = AgentNotificationLogContent.recent(AgentExecutionLogStore.snapshot(), task)
        return buildString {
            append(context.getString(R.string.agent_notification_logs_hint))
            if (events.isEmpty()) append('\n').append(context.getString(R.string.agent_logs_empty))
            for (event in events) {
                append('\n').append(if (event.tool == AgentLogTool.NONE) AgentStatusText.phase(context, event.phase) else AgentStatusText.tool(context, event.tool))
                append(" · ").append(AgentStatusText.status(context, event.status))
                if (event.source > 0) append(" · ").append(context.getString(R.string.agent_logs_source, event.source, AgentStatusText.role(context, event.role)))
                append('\n').append(context.getString(R.string.agent_logs_metrics, event.durationMs, event.inputTokens, event.outputTokens))
                AgentStatusText.reason(context, event.reason).takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
            }
        }.take(AgentNotificationLogContent.MAX_CHARS)
    }
    fun settingsIntent(context: Context): Intent {
        val base = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT < 36) return base
        val promoted = AgentPromotedNotification.settingsIntent(context)
        return if (promoted.resolveActivity(context.packageManager) != null) promoted else base
    }
}

/** 隔离新 API，低版本既不加载 ProgressStyle，也不请求新版授权界面。 */
@RequiresApi(36)
private object AgentPromotedNotification {
    fun configure(builder: Notification.Builder, manager: NotificationManager, tip: String, allowed: Boolean, logMode: Boolean) {
        if (!logMode) builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
        builder.setShortCriticalText(tip.take(7))
            // SDK 37 将 native setter 标为 36.1；36.0 使用同一个标准通知 extra，系统不支持提升时自然降为普通通知。
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", allowed && manager.canPostPromotedNotifications())
            })
    }
    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
