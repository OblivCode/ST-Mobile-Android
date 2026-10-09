package app.stmobile.foreground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import app.stmobile.MainActivity
import app.stmobile.R
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus

/**
 * Encapsulates notification channel configuration and foreground notification construction
 * with dynamic status messages and native chronometer countdowns for [ForegroundService].
 */
object ServiceNotificationHelper {

    const val CHANNEL_ID = "st_mobile_node"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        status: NodeStatus,
        isGenerating: Boolean = false,
        idleTargetEpochMs: Long = 0L,
        customText: String? = null,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, ForegroundService::class.java).setAction(ForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val displayText = when {
            customText != null -> customText
            isGenerating -> context.getString(R.string.status_generating)
            idleTargetEpochMs > System.currentTimeMillis() -> context.getString(R.string.status_running)
            status.state == NodeState.STOPPING -> context.getString(R.string.status_stopping)
            status.state == NodeState.STARTING -> context.getString(R.string.status_starting)
            status.message.isNotBlank() -> status.message
            else -> status.state.name
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(displayText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(status.isActive)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notification_action_stop),
                    stopIntent,
                ).build()
            )

        val isCountdownActive = !isGenerating && idleTargetEpochMs > System.currentTimeMillis()
        if (isCountdownActive) {
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(idleTargetEpochMs)
            builder.setShowWhen(true)
        } else {
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
        }

        return builder.build()
    }

    fun startForeground(service: Service, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
