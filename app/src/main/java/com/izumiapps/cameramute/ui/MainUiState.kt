package com.izumiapps.cameramute.ui

import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState

data class MainUiState(
    val shizukuState: ShizukuConnectionState = ShizukuConnectionState.NOT_AVAILABLE,
    val deviceUnverified: Boolean = false,
    val deviceModel: String = "",
    val deviceDisplay: String = "",
    val forceUseState: ForceUseState? = null,
    val isBusy: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val consentAccepted: Boolean = false,
    val silentOrVibrate: Boolean = false,
    val lastMessage: String? = null,
)

enum class EnableStep {
    CHECKING, CONNECT_SHIZUKU, ALLOW_SHIZUKU, CHANGE_SHIZUKU_BACKEND, READ_STATE,
    RESTORE, UNMANAGED, UNEXPECTED, CONSENT, NOTIFICATIONS,
    SILENT_MODE, READY,
}

/** ボタンの有効条件と次の操作案内を同じ判定から作る。 */
fun MainUiState.enableStep(): EnableStep = when {
    isBusy -> EnableStep.CHECKING
    primaryStatus() == PrimaryStatus.SHIZUKU_DISCONNECTED -> EnableStep.CONNECT_SHIZUKU
    primaryStatus() == PrimaryStatus.SHIZUKU_PERMISSION_DENIED -> EnableStep.ALLOW_SHIZUKU
    primaryStatus() == PrimaryStatus.SHIZUKU_UNSUPPORTED_BACKEND -> EnableStep.CHANGE_SHIZUKU_BACKEND
    primaryStatus() == PrimaryStatus.UNKNOWN -> EnableStep.READ_STATE
    primaryStatus() == PrimaryStatus.MUTE_ASSIST_ACTIVE -> EnableStep.RESTORE
    primaryStatus() == PrimaryStatus.UNMANAGED_UNFORCED -> EnableStep.UNMANAGED
    primaryStatus() == PrimaryStatus.UNEXPECTED_VALUE -> EnableStep.UNEXPECTED
    !consentAccepted -> EnableStep.CONSENT
    !notificationPermissionGranted -> EnableStep.NOTIFICATIONS
    !silentOrVibrate -> EnableStep.SILENT_MODE
    else -> EnableStep.READY
}

fun MainUiState.canEnable(): Boolean = enableStep() == EnableStep.READY

fun MainUiState.canRequestShizukuPermission(): Boolean = !isBusy && shizukuState.canRequestPermission

fun MainUiState.canRestore(): Boolean = !isBusy &&
    primaryStatus() in setOf(PrimaryStatus.MUTE_ASSIST_ACTIVE, PrimaryStatus.UNKNOWN)

/** Shizukuの接続状態と端末から取得した設定値から導く表示状態。 */
enum class PrimaryStatus {
    SHIZUKU_DISCONNECTED,
    SHIZUKU_PERMISSION_DENIED,
    SHIZUKU_UNSUPPORTED_BACKEND,
    UNKNOWN,
    GENUINE,
    MUTE_ASSIST_ACTIVE,
    UNEXPECTED_VALUE,
    UNMANAGED_UNFORCED,
}

/** Shizukuの接続状態を確認してから、実際のforce use値を表示する。 */
fun MainUiState.primaryStatus(): PrimaryStatus = when {
    shizukuState == ShizukuConnectionState.NOT_AVAILABLE -> PrimaryStatus.SHIZUKU_DISCONNECTED
    shizukuState == ShizukuConnectionState.PERMISSION_DENIED -> PrimaryStatus.SHIZUKU_PERMISSION_DENIED
    shizukuState == ShizukuConnectionState.UNSUPPORTED_BACKEND -> PrimaryStatus.SHIZUKU_UNSUPPORTED_BACKEND
    forceUseState == null -> PrimaryStatus.UNKNOWN
    forceUseState is ForceUseState.GenuineEnforced -> PrimaryStatus.GENUINE
    forceUseState is ForceUseState.MuteAssistActive -> PrimaryStatus.MUTE_ASSIST_ACTIVE
    forceUseState is ForceUseState.UnmanagedUnforced -> PrimaryStatus.UNMANAGED_UNFORCED
    else -> PrimaryStatus.UNEXPECTED_VALUE
}
