package com.izumiapps.cameramute

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.izumiapps.cameramute.notification.MuteAssistNotifier
import com.izumiapps.cameramute.timer.RestoreBroadcastReceiver
import com.izumiapps.cameramute.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.shadows.ShadowPendingIntent

private const val FAILURE_CHANNEL_ID = "mute_assist_restore_failure"

@RunWith(RobolectricTestRunner::class)
class MuteAssistNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: MuteAssistNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        ShadowPendingIntent.reset()
        manager.cancelAll()
        manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
        shadowOf(manager).setNotificationsEnabled(true)
        setNotificationPermission(granted = true)
        notifier = MuteAssistNotifier(context)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
        ShadowPendingIntent.reset()
    }

    @Test
    fun `status notification is unavailable when runtime permission is denied`() {
        setNotificationPermission(granted = false)

        assertFalse(notifier.canShowStatus())
        assertFalse(notifier.showManualActive())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `status notification is unavailable when app notifications are disabled`() {
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(notifier.canShowStatus())
        assertFalse(notifier.showManualActive())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `missing status channel does not allow enable`() {
        manager.deleteNotificationChannel(MuteAssistNotifier.CHANNEL_ID)

        assertFalse(notifier.canShowStatus())
        assertFalse(notifier.showManualActive())
    }

    @Test
    fun `status notification is unavailable when its channel importance is none`() {
        manager.deleteNotificationChannel(MuteAssistNotifier.CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                MuteAssistNotifier.CHANNEL_ID,
                "disabled status",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertFalse(notifier.canShowStatus())
        assertFalse(notifier.showManualActive())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `manual active notification explains restoration and opens app or restores`() {
        assertTrue(notifier.showManualActive())

        val posted = manager.activeNotifications.single()
        val notification = posted.notification
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(1001, posted.id)
        assertEquals(MuteAssistNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("無音補助オン（手動復元）", title)
        assertEquals(context.getString(R.string.notification_text_body), body)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertOpenAppIntent(notification.contentIntent)

        val restoreAction = notification.actions.single()
        val restoreIntent = shadowOf(restoreAction.actionIntent)
        assertTrue(restoreIntent.isBroadcast)
        assertEquals(RestoreBroadcastReceiver.ACTION_RESTORE, restoreIntent.savedIntent.action)
        assertEquals(RestoreBroadcastReceiver::class.java.name, restoreIntent.savedIntent.component?.className)
    }

    @Test
    fun `restore failure uses a separate channel and opens the app`() {
        notifier.showRestoreFailed()

        val posted = manager.activeNotifications.single()
        assertEquals(1002, posted.id)
        assertEquals(FAILURE_CHANNEL_ID, posted.notification.channelId)
        assertFalse(posted.notification.channelId == MuteAssistNotifier.CHANNEL_ID)
        assertOpenAppIntent(posted.notification.contentIntent)
    }

    @Test
    fun `restore failure text covers causes other than disconnection`() {
        notifier.showRestoreFailed()

        val notification = manager.activeNotifications.single().notification
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(context.getString(R.string.notification_restore_failed_body), body)
        listOf("切断", "権限", "起動方式", "案内に沿って")
            .forEach { assertTrue("Missing cause or guidance: $it", body.contains(it)) }
    }

    @Test
    fun `cancel all removes both active and failure notifications`() {
        assertTrue(notifier.showManualActive())
        notifier.showRestoreFailed()
        assertEquals(setOf(1001, 1002), manager.activeNotifications.map { it.id }.toSet())

        notifier.cancelAll()

        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun setNotificationPermission(granted: Boolean) {
        val shadow = shadowOf(ShadowInstrumentation.getInstrumentation())
        val method = shadow.javaClass.getDeclaredMethod(
            if (granted) "grantPermissions" else "denyPermissions",
            Array<String>::class.java,
        )
        method.isAccessible = true
        method.invoke(shadow, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun assertOpenAppIntent(pendingIntent: PendingIntent?) {
        requireNotNull(pendingIntent)
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isActivity)
        assertEquals(MainActivity::class.java.name, shadow.savedIntent.component?.className)
    }
}
