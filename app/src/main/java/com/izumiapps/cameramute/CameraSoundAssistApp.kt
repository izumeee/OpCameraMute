package com.izumiapps.cameramute

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.izumiapps.cameramute.assist.ManualAssistController
import com.izumiapps.cameramute.assist.PreferencesAssistSafetyStore
import com.izumiapps.cameramute.audiopolicy.AudioPolicyRepository
import com.izumiapps.cameramute.audiopolicy.PreferencesAudioRecoveryStore
import com.izumiapps.cameramute.device.DeviceGuard
import com.izumiapps.cameramute.notification.MuteAssistNotifier
import com.izumiapps.cameramute.shizuku.RealShizukuGateway
import com.izumiapps.cameramute.shizuku.ShizukuGateway

/** 起動時に音設定を変更しない。全操作入口で単一のControllerを共有する。 */
class CameraSoundAssistApp : Application() {
    val deviceGuard: DeviceGuard by lazy { DeviceGuard() }
    val shizukuGateway: ShizukuGateway by lazy { RealShizukuGateway(this) }
    private val notifier by lazy { MuteAssistNotifier(this) }
    private val repository by lazy {
        AudioPolicyRepository(
            shizukuGateway = shizukuGateway,
            deviceGuard = deviceGuard,
            recoveryStore = PreferencesAudioRecoveryStore(
                prefs = getSharedPreferences("audio_recovery_v1", Context.MODE_PRIVATE),
                deviceKey = deviceGuard.safetyKey,
                bootCount = { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) },
            ),
            portProvider = { shizukuGateway.obtainAudioPolicyPort() },
        )
    }
    val manualAssistController by lazy {
        ManualAssistController(
            repository = repository,
            safety = PreferencesAssistSafetyStore(this, deviceGuard.safetyKey),
            notifications = notifier,
            resources = resources,
            isSilentOrVibrate = {
                getSystemService(AudioManager::class.java).ringerMode.let {
                    it == AudioManager.RINGER_MODE_SILENT || it == AudioManager.RINGER_MODE_VIBRATE
                }
            },
        )
    }
}
