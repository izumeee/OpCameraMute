package com.izumiapps.cameramute.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** 通知設定を優先し、端末が対応しない場合はアプリ詳細を開く。 */
internal fun launchNotificationSettings(
    packageName: String,
    launch: (Intent) -> Unit,
): Boolean {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:$packageName".toUri(),
    )
    for (intent in listOf(notificationSettings, appDetails)) {
        try {
            launch(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // メーカーの設定アプリがこの画面に対応していない場合は次の候補を試す。
        } catch (_: SecurityException) {
            // 端末の制限で画面を開けない場合も、別の設定画面を試す。
        }
    }
    return false
}
