package com.izumiapps.cameramute.audiopolicy

import com.izumiapps.cameramute.device.DeviceGuard
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.shizuku.ShizukuGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class OperationFailure {
    data object ShizukuNotAvailable : OperationFailure()
    data object ShizukuPermissionDenied : OperationFailure()
    data object UnsupportedShizukuBackend : OperationFailure()
    data object RecoveryStateUnavailable : OperationFailure()
    data class UnexpectedCurrentValue(val rawValue: Int) : OperationFailure()
    data class VerificationMismatch(val expected: Int, val actual: Int) : OperationFailure()
    data class BinderError(val message: String) : OperationFailure()
}

sealed class RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>()
    data class Failure(val reason: OperationFailure) : RepositoryResult<Nothing>()
}

/**
 * Shizuku → 名前で解決した端末API → 現在値 → 復元記録の同期保存 →
 * 変更 → 再取得の順で処理する。設定値の一致は実際の音・副作用の保証ではない。
 * 未検証端末の復元では、同じビルドで本アプリが記録した変更前の11が必須。
 */
class AudioPolicyRepository(
    private val shizukuGateway: ShizukuGateway,
    private val deviceGuard: DeviceGuard,
    private val recoveryStore: AudioRecoveryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val portProvider: suspend () -> AudioPolicyPort,
) {
    suspend fun refreshState(): RepositoryResult<ForceUseState> = withContext(ioDispatcher) {
        withPort { port ->
            val current = port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
            if (current == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED) {
                // 自然復元・setter失敗後も、11を確認した時点で変更の所有記録を終了する。
                finishRestore(ForceUseState.GenuineEnforced)
            } else if (current == AudioPolicyConstants.CONFIG_FORCE_NONE && deviceGuard.isUnverified) {
                val original = loadRecovery()
                when {
                    original == null -> RepositoryResult.Success(ForceUseState.UnmanagedUnforced)
                    original == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED ->
                        RepositoryResult.Success(ForceUseState.MuteAssistActive)
                    else -> RepositoryResult.Failure(OperationFailure.RecoveryStateUnavailable)
                }
            } else RepositoryResult.Success(ForceUseState.fromRaw(current))
        }
    }

    suspend fun enableMuteAssist(): RepositoryResult<ForceUseState> = withContext(ioDispatcher) {
        withPort { port ->
            val before = port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
            // すでに0の場合も「元の値」として保存しない。
            if (before != AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED) {
                return@withPort RepositoryResult.Failure(OperationFailure.UnexpectedCurrentValue(before))
            }
            try {
                recoveryStore.save(before)
            } catch (_: Exception) {
                return@withPort RepositoryResult.Failure(OperationFailure.RecoveryStateUnavailable)
            }
            // 以後は結果不明でも復元記録を残す。
            writeAndVerify(port, AudioPolicyConstants.CONFIG_FORCE_NONE)
        }
    }

    suspend fun restoreGenuine(): RepositoryResult<ForceUseState> = withContext(ioDispatcher) {
        withPort { port ->
            val before = port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
            if (ForceUseState.fromRaw(before) is ForceUseState.Unexpected) {
                return@withPort RepositoryResult.Failure(OperationFailure.UnexpectedCurrentValue(before))
            }
            // 11なら変更は不要。読み取りで復元済みを確認してから記録だけを消す。
            if (before == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED) {
                return@withPort finishRestore(ForceUseState.GenuineEnforced)
            }
            val original = if (deviceGuard.isVerified) {
                // 変更前の記録がない0でも、実機で基準値11を確認したビルドだけ復元する。
                AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED
            } else {
                loadRecovery()
            }
            if (original != AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED) {
                return@withPort RepositoryResult.Failure(OperationFailure.RecoveryStateUnavailable)
            }
            when (val restored = writeAndVerify(port, original)) {
                is RepositoryResult.Success -> finishRestore(restored.value)
                is RepositoryResult.Failure -> restored
            }
        }
    }

    private fun loadRecovery(): Int? = try {
        recoveryStore.load()
    } catch (e: Exception) {
        throw RecoveryReadException(e)
    }

    private class RecoveryReadException(cause: Exception) : Exception(cause)

    private fun finishRestore(state: ForceUseState): RepositoryResult<ForceUseState> = try {
        recoveryStore.clear()
        RepositoryResult.Success(state)
    } catch (_: Exception) {
        RepositoryResult.Failure(OperationFailure.RecoveryStateUnavailable)
    }

    private fun writeAndVerify(port: AudioPolicyPort, target: Int): RepositoryResult<ForceUseState> {
        port.setForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM, target)
        val after = port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        return if (after == target) {
            RepositoryResult.Success(ForceUseState.fromRaw(after))
        } else RepositoryResult.Failure(OperationFailure.VerificationMismatch(target, after))
    }

    private fun gatewayFailureOrNull(): OperationFailure? = when (shizukuGateway.connectionState.value) {
        ShizukuConnectionState.NOT_AVAILABLE -> OperationFailure.ShizukuNotAvailable
        ShizukuConnectionState.PERMISSION_DENIED -> OperationFailure.ShizukuPermissionDenied
        ShizukuConnectionState.UNSUPPORTED_BACKEND -> OperationFailure.UnsupportedShizukuBackend
        ShizukuConnectionState.READY -> null
    }

    private suspend fun withPort(
        operation: (AudioPolicyPort) -> RepositoryResult<ForceUseState>,
    ): RepositoryResult<ForceUseState> {
        gatewayFailureOrNull()?.let { return RepositoryResult.Failure(it) }
        return try {
            val port = portProvider()
            gatewayFailureOrNull()?.let { return RepositoryResult.Failure(it) }
            operation(port)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RecoveryReadException) {
            RepositoryResult.Failure(OperationFailure.RecoveryStateUnavailable)
        } catch (e: Exception) {
            RepositoryResult.Failure(OperationFailure.BinderError(e.message ?: e.javaClass.simpleName))
        }
    }
}
