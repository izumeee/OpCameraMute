package com.izumiapps.cameramute.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationSettingsLauncherTest {
    private val packageName = "com.izumiapps.cameramute.free"

    @Test
    fun `notification settings includes this app and succeeds without fallback`() {
        val launched = mutableListOf<Intent>()

        val opened = launchNotificationSettings(packageName) { launched += it }

        assertTrue(opened)
        assertEquals(1, launched.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, launched.single().action)
        assertEquals(packageName, launched.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `missing notification activity falls back to this app details`() {
        val launched = mutableListOf<Intent>()

        val opened = launchNotificationSettings(packageName) {
            launched += it
            if (it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                throw ActivityNotFoundException()
            }
        }

        assertTrue(opened)
        assertEquals(2, launched.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launched.last().action)
        assertEquals("package:$packageName", launched.last().data.toString())
    }

    @Test
    fun `restricted notification activity also falls back to app details`() {
        val launched = mutableListOf<Intent>()

        val opened = launchNotificationSettings(packageName) {
            launched += it
            if (it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                throw SecurityException("Setting is restricted")
            }
        }

        assertTrue(opened)
        assertEquals(2, launched.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launched.last().action)
    }

    @Test
    fun `missing settings activities reports failure without throwing`() {
        val attempts = mutableListOf<String?>()

        val opened = launchNotificationSettings(packageName) {
            attempts += it.action
            throw ActivityNotFoundException()
        }

        assertFalse(opened)
        assertEquals(
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            attempts,
        )
    }

    @Test
    fun `restricted app details reports failure without throwing`() {
        var attempts = 0

        val opened = launchNotificationSettings(packageName) {
            attempts += 1
            if (it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                throw ActivityNotFoundException()
            }
            throw SecurityException("App settings are restricted")
        }

        assertFalse(opened)
        assertEquals(2, attempts)
    }
}
