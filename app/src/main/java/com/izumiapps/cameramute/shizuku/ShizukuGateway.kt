package com.izumiapps.cameramute.shizuku

import com.izumiapps.cameramute.audiopolicy.AudioPolicyPort
import kotlinx.coroutines.flow.StateFlow

enum class ShizukuConnectionState {
    /** Shizukuアプリ未インストール、未起動、またはBinder死亡後。 */
    NOT_AVAILABLE,

    /** Shizukuは起動しているが、このアプリへの権限が未許可。 */
    PERMISSION_DENIED,

    /** 接続済みだが、root方式などshell以外の起動方式は未対応。 */
    UNSUPPORTED_BACKEND,

    /** 利用可能。 */
    READY;

    /** 権限リクエストを実行できるのは、対応する接続で未許可の場合だけ。 */
    val canRequestPermission: Boolean
        get() = this == PERMISSION_DENIED
}

interface ShizukuGateway {
    val connectionState: StateFlow<ShizukuConnectionState>

    fun requestPermission(requestCode: Int)

    /**
     * FOR_SYSTEM専用の名前付き端末APIポートを非同期で取得する。
     *
     * @throws IllegalStateException Shizukuが[ShizukuConnectionState.READY]でない場合、
     * または端末のnamed AudioSystem APIを安全に利用できない場合
     */
    suspend fun obtainAudioPolicyPort(): AudioPolicyPort
}
