package com.izumiapps.cameramute.ui

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.izumiapps.cameramute.CameraSoundAssistApp
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.ui.theme.CameraMuteAssistTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application as CameraSoundAssistApp)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CameraMuteAssistTheme {
                val uiState by viewModel.uiState.collectAsState()
                MainScreen(
                    uiState = uiState,
                    onEnableAssist = viewModel::enableAssist,
                    onRestoreGenuine = viewModel::restoreGenuine,
                    onRefresh = viewModel::refresh,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onRequestNotificationPermission = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) openNotificationSettings()
                        else notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenShizuku = { openShizukuApp() },
                    onOpenSoundSettings = ::openSoundSettings,
                    onAcceptSafety = viewModel::acceptSafety,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // タイル・通知・外部操作で値が変わっている可能性があるため、画面復帰時に再取得する。
        viewModel.refresh()
    }

    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.sound_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotificationSettings() {
        if (!launchNotificationSettings(packageName) { startActivity(it) }) {
            Toast.makeText(this, R.string.notification_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$SHIZUKU_PACKAGE".toUri()))
        }
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
