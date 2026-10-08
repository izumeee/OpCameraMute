package com.izumiapps.cameramute.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.izumiapps.cameramute.BuildConfig
import com.izumiapps.cameramute.CameraSoundAssistApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 通知アクションから復元を受け付け、結果の検証まで共通Controllerで完了させる。 */
class RestoreBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE) return
        val app = context.applicationContext as CameraSoundAssistApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.manualAssistController.restoreFromNotification(app.shizukuGateway.connectionState)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTORE = BuildConfig.APPLICATION_ID + ".action.RESTORE"
    }
}
