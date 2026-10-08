package com.izumiapps.cameramute

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.izumiapps.cameramute.timer.RestoreBroadcastReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApplicationIdentityTest {
    @Test
    fun `shipping identity remains consistent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = context.assets.open("legal/privacy-policy-ja.txt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertEquals(EXPECTED_APPLICATION_ID, BuildConfig.APPLICATION_ID)
        assertEquals(EXPECTED_APPLICATION_ID, context.packageName)
        assertEquals("$EXPECTED_APPLICATION_ID.action.RESTORE", RestoreBroadcastReceiver.ACTION_RESTORE)
        assertTrue(policy.contains("パッケージ名：$EXPECTED_APPLICATION_ID、"))
        assertTrue(policy.contains("別のパッケージIDのアプリから同意や復元記録を移行する機能はありません"))
    }

    private companion object {
        const val EXPECTED_APPLICATION_ID = "com.izumiapps.cameramute.free"
    }
}
