package com.izumiapps.cameramute.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService
import com.izumiapps.cameramute.CameraSoundAssistApp
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

/** アプリと同じ同意・通知条件で手動切替を行う。 */
class CameraSoundAssistTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val app = application as CameraSoundAssistApp
        scope?.launch {
            combine(app.manualAssistController.state, app.shizukuGateway.connectionState) { _, _ -> Unit }
                .collect { renderTileState() }
        }
        scope?.launch {
            app.shizukuGateway.connectionState.refreshOnReady {
                app.manualAssistController.refresh()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        super.onClick()
        scope?.launch { handleClick() }
    }

    private suspend fun handleClick() {
        val app = application as CameraSoundAssistApp
        // 読取→判断→書込→検証→通知までを共通ロック内で完了させる。
        if (!app.manualAssistController.toggle()) openApp()
        renderTileState()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pendingIntent)
    }

    private fun renderTileState() {
        val app = application as CameraSoundAssistApp
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_camera_mute)

        val assist = app.manualAssistController.state.value
        val appearance = tileAppearance(
            connection = app.shizukuGateway.connectionState.value,
            forceUseState = assist.forceUseState,
            isBusy = assist.isBusy,
        )
        tile.state = appearance.state
        tile.subtitle = getString(appearance.subtitleRes)
        tile.updateTile()
    }
}
