package com.izumiapps.cameramute.assist

import android.content.res.Resources
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.audiopolicy.AudioPolicyRepository
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.audiopolicy.OperationFailure
import com.izumiapps.cameramute.audiopolicy.RepositoryResult
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ManualAssistState(
    val forceUseState: ForceUseState? = null,
    val isBusy: Boolean = false,
    val consentAccepted: Boolean = false,
    val notificationAvailable: Boolean = false,
    val silentOrVibrate: Boolean = false,
    val lastMessage: String? = null,
)

/** UI・タイル・通知の唯一の操作入口。安全条件を共通ロック内で確認する。 */
class ManualAssistController(
    private val repository: AudioPolicyRepository,
    private val safety: AssistSafetyStore,
    private val notifications: AssistNotifications,
    private val isSilentOrVibrate: () -> Boolean,
    private val resources: Resources,
) {
    private val mutex = Mutex()
    private val restoreNotificationLock = Any()
    private var verifiedRestoreGeneration = 0L
    private val mutableState = MutableStateFlow(ManualAssistState())
    val state: StateFlow<ManualAssistState> = mutableState.asStateFlow()

    suspend fun refresh(): Boolean = operation { readCurrent() != null }

    suspend fun enable(): Boolean = operation {
        when (readCurrent()) {
            ForceUseState.GenuineEnforced -> enableAfterRead()
            ForceUseState.MuteAssistActive -> reject(resources.getString(R.string.assist_already_changed))
            ForceUseState.UnmanagedUnforced -> rejectUnmanagedUnforced()
            is ForceUseState.Unexpected -> reject(resources.getString(R.string.assist_unexpected_setting))
            null -> false
        }
    }

    suspend fun restore(): Boolean = operation { restoreGenuine() }

    /**
     * 通知による冷起動ではBinder到着と共通ロックを合計2秒まで待つ。
     * 復元開始後は既存のUserService bind上限5秒と検証・通知を完了させる。
     */
    suspend fun restoreFromNotification(
        connectionState: StateFlow<ShizukuConnectionState>,
    ): Boolean {
        val requestedGeneration = synchronized(restoreNotificationLock) { verifiedRestoreGeneration }
        var completedResult: Boolean? = null
        withTimeoutOrNull(NOTIFICATION_STARTUP_TIMEOUT_MILLIS) {
            mutex.withLock {
                connectionState.first { it != ShizukuConnectionState.NOT_AVAILABLE }
                currentCoroutineContext().ensureActive()
                // NonCancellableな検証完了後に外側の期限が到来していても、結果を失わない。
                completedResult = performOperation { restoreGenuine() }
            }
        }
        return completedResult ?: run {
            synchronized(restoreNotificationLock) {
                // 待機中に別の操作が復元を確認した場合、その成功通知取消を上書きしない。
                if (verifiedRestoreGeneration == requestedGeneration) notifications.showRestoreFailed()
            }
            // ロック取得前の失敗で、進行中の別操作の画面状態を上書きしない。
            false
        }
    }

    suspend fun toggle(): Boolean = operation {
        when (readCurrent()) {
            ForceUseState.GenuineEnforced -> enableAfterRead()
            ForceUseState.MuteAssistActive -> restoreGenuine()
            ForceUseState.UnmanagedUnforced -> rejectUnmanagedUnforced()
            is ForceUseState.Unexpected -> reject(resources.getString(R.string.assist_unexpected_setting))
            null -> false
        }
    }

    suspend fun acceptSafety(): Boolean = operation {
        if (readCurrent() == null) return@operation false
        safety.consentAccepted = true
        true
    }

    private suspend fun enableAfterRead(): Boolean {
        if (!safety.consentAccepted) return reject(resources.getString(R.string.assist_consent_required))
        if (!notifications.canShowStatus()) return reject(resources.getString(R.string.assist_notification_permission_required))
        if (!isSilentOrVibrate()) return reject(resources.getString(R.string.assist_silent_mode_required))
        when (val result = repository.enableMuteAssist()) {
            is RepositoryResult.Failure -> {
                // setter後の読取失敗では設定が変更済みの可能性もある。
                notifications.showRestoreFailed()
                return fail(result.reason)
            }
            is RepositoryResult.Success -> mutableState.update { it.copy(forceUseState = result.value) }
        }

        // setter開始後は呼出元のキャンセルで検証/保存/通知を中断させない。
        try {
            if (!notifications.showManualActive()) return rollbackEnable(resources.getString(R.string.assist_notification_failed))
        } catch (_: Exception) {
            return rollbackEnable(resources.getString(R.string.assist_active_notification_failed))
        }
        return true
    }

    private suspend fun rollbackEnable(reason: String): Boolean {
        val restored = restoreGenuine()
        mutableState.update { it.copy(lastMessage = if (restored) resources.getString(R.string.assist_rollback_completed, reason)
            else resources.getString(R.string.assist_rollback_unconfirmed, reason)) }
        return false
    }

    private suspend fun restoreGenuine(): Boolean {
        val result = try {
            repository.restoreGenuine()
        } catch (_: TimeoutCancellationException) {
            RepositoryResult.Failure(OperationFailure.BinderError("Shizuku connection timed out"))
        }
        when (result) {
            is RepositoryResult.Failure -> {
                notifications.showRestoreFailed()
                return fail(result.reason)
            }
            is RepositoryResult.Success -> {
                mutableState.update { it.copy(forceUseState = result.value) }
                // 復元を実測できるまで、復元通知を消さない。
                clearNotificationsAfterVerifiedRestore()
                return true
            }
        }
    }

    private suspend fun readCurrent(): ForceUseState? = when (val result = repository.refreshState()) {
        is RepositoryResult.Failure -> { fail(result.reason); null }
        is RepositoryResult.Success -> {
            mutableState.update { it.copy(forceUseState = result.value) }
            when (result.value) {
                ForceUseState.GenuineEnforced -> {
                    clearNotificationsAfterVerifiedRestore()
                }
                ForceUseState.MuteAssistActive -> notifications.showManualActive()
                ForceUseState.UnmanagedUnforced -> Unit
                is ForceUseState.Unexpected -> Unit
            }
            result.value
        }
    }

    private fun clearNotificationsAfterVerifiedRestore() = synchronized(restoreNotificationLock) {
        // 通知取消に失敗しても、音設定の復元を確認した事実は保持する。
        verifiedRestoreGeneration += 1
        notifications.cancelAll()
    }

    private suspend fun operation(block: suspend () -> Boolean): Boolean = mutex.withLock {
        performOperation(block)
    }

    private suspend fun performOperation(block: suspend () -> Boolean): Boolean =
        withContext(NonCancellable) {
            mutableState.update { it.copy(isBusy = true, forceUseState = null, lastMessage = null) }
            try {
                block()
            } catch (_: Exception) {
                mutableState.update { it.copy(forceUseState = null, lastMessage =
                    resources.getString(R.string.assist_operation_unconfirmed)) }
                false
            } finally {
                // 補助情報が壊れていてもbusyを残さず、次の復元操作を妨げない。
                val snapshot = runCatching {
                    mutableState.value.copy(
                        isBusy = false,
                        consentAccepted = safety.consentAccepted,
                        notificationAvailable = notifications.canShowStatus(),
                        silentOrVibrate = isSilentOrVibrate(),
                    )
                }.getOrElse {
                    mutableState.value.copy(
                        isBusy = false,
                        consentAccepted = false,
                        notificationAvailable = false,
                        silentOrVibrate = false,
                        lastMessage = resources.getString(R.string.assist_safety_state_unavailable),
                    )
                }
                mutableState.value = snapshot
            }
        }

    private fun reject(message: String): Boolean {
        mutableState.update { it.copy(lastMessage = message) }
        return false
    }

    private fun rejectUnmanagedUnforced(): Boolean = reject(
        resources.getString(R.string.assist_unmanaged_setting),
    )

    private fun fail(reason: OperationFailure): Boolean {
        mutableState.update { it.copy(forceUseState = null) }
        return reject(when (reason) {
            OperationFailure.ShizukuNotAvailable -> resources.getString(R.string.assist_shizuku_unavailable)
            OperationFailure.UnsupportedShizukuBackend -> resources.getString(R.string.shizuku_unsupported_backend_guidance)
            OperationFailure.ShizukuPermissionDenied -> resources.getString(R.string.assist_shizuku_permission_denied)
            OperationFailure.RecoveryStateUnavailable -> resources.getString(R.string.assist_recovery_state_unavailable)
            is OperationFailure.UnexpectedCurrentValue -> resources.getString(R.string.assist_unsupported_setting)
            is OperationFailure.VerificationMismatch -> resources.getString(R.string.assist_verification_failed)
            is OperationFailure.BinderError -> resources.getString(R.string.assist_audio_operation_failed)
        })
    }

    private companion object {
        const val NOTIFICATION_STARTUP_TIMEOUT_MILLIS = 2_000L
    }

}
