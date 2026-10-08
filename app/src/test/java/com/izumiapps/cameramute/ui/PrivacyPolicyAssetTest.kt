package com.izumiapps.cameramute.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.izumiapps.cameramute.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivacyPolicyAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `offline policy identifies the source published app and operator`() {
        val policy = readPolicy()
        listOf("プライバシーポリシー", context.getString(R.string.app_name), context.packageName,
            "イズミアプリケーション", "プライバシーに関する連絡先：izumi@izumiapps.com")
            .forEach { assertTrue("Missing identity: $it", policy.contains(it)) }
    }

    @Test fun `offline policy describes local storage without operator data transfer`() {
        val policy = readPolicy()
        listOf("本アプリ専用の保存領域へ保存します。",
            "端末情報や保存内容を運営者へ送信する機能はありません。")
            .forEach { assertTrue("Missing disclosure: $it", policy.contains(it)) }
    }

    @Test fun `offline policy covers local safety data and restore before deletion`() {
        val policy = readPolicy()
        listOf("起動回数", "Shizuku", "削除", "純正状態に戻す", "メーカー・ブランド",
            "変更前の音設定", "別のパッケージID", "メールの受信・保管にはZoho Mailを利用します。")
            .forEach { assertTrue("Missing disclosure: $it", policy.contains(it)) }
    }

    @Test fun `policy paragraphs are the same for LF and CRLF checkouts`() {
        val lf = readPolicy().replace("\r\n", "\n")
        val paragraphs = privacyPolicyParagraphs(lf)

        assertEquals(paragraphs, privacyPolicyParagraphs(lf.replace("\n", "\r\n")))
        assertFalse(paragraphs.first().startsWith("プライバシーポリシー"))
        assertTrue(paragraphs.size > 1)
        assertTrue(paragraphs.none { "\r" in it })
    }

    private fun readPolicy(): String = context.assets.open("legal/privacy-policy-ja.txt")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
