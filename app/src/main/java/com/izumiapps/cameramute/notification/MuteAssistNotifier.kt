package com.izumiapps.cameramute.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.assist.AssistNotifications
import com.izumiapps.cameramute.timer.RestoreBroadcastReceiver
import com.izumiapps.cameramute.ui.MainActivity

/** 通知は最後に確認した設定の案内。実音や常時監視を保証しない。 */
class MuteAssistNotifier(private val context: Context) : AssistNotifications {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_description); setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            FAILURE_CHANNEL_ID, context.getString(R.string.notification_failure_channel_name), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { setShowBadge(false) })
    }

    override fun canShowStatus(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 2002, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun restoreIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 2001, Intent(context, RestoreBroadcastReceiver::class.java).apply {
            action = RestoreBroadcastReceiver.ACTION_RESTORE
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun showManualActive(): Boolean = showActive(
        context.getString(R.string.notification_active_title), context.getString(R.string.notification_text_body),
    )

    private fun showActive(title: String, body: String): Boolean {
        if (!canShowStatus()) return false
        return notifySafely(NOTIFICATION_ID_ACTIVE, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_camera_mute)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.notification_action_restore), restoreIntent())
            .build())
    }

    override fun cancelAll() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_ACTIVE)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_FAILURE)
    }

    override fun showRestoreFailed() {
        val body = context.getString(R.string.notification_restore_failed_body)
        notifySafely(NOTIFICATION_ID_FAILURE, NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_camera_mute)
            .setContentTitle(context.getString(R.string.notification_restore_failed_title))
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent()).setAutoCancel(true)
            .addAction(0, context.getString(R.string.notification_action_restore), restoreIntent()).build())
    }

    private fun notifySafely(id: Int, notification: android.app.Notification): Boolean = try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) false
        else { NotificationManagerCompat.from(context).notify(id, notification); true }
    } catch (_: SecurityException) { false }

    companion object {
        const val CHANNEL_ID = "mute_assist_status"
        private const val FAILURE_CHANNEL_ID = "mute_assist_restore_failure"
        private const val NOTIFICATION_ID_ACTIVE = 1001
        private const val NOTIFICATION_ID_FAILURE = 1002
    }
}
