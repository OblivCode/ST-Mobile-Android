package app.stmobile.service

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
import app.stmobile.models.NodeStatus

/**
 * Encapsulates notification channel configuration and foreground notification construction
 * for [NodeForegroundService].
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

    fun buildNotification(context: Context, status: NodeStatus, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, NodeForegroundService::class.java).setAction(NodeForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
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
            .build()
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
