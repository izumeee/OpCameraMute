package com.izumiapps.cameramute.audiopolicy

import com.izumiapps.cameramute.shizuku.IAudioPolicyUserService

/** テスト時に端末APIやBinderを介さないFakeへ差し替えるためのポート。 */
interface AudioPolicyPort {
    fun getForceUse(usage: Int): Int
    fun setForceUse(usage: Int, config: Int)
}

/**
 * FOR_SYSTEMだけを公開するShizuku UserServiceを既存の[AudioPolicyPort]へ適合させる。
 * raw transaction番号やParcelは使用せず、usage/configの範囲をIPC前に再検証する。
 */
class AudioPolicyBinderClient(
    private val service: IAudioPolicyUserService,
    private val isValid: () -> Boolean = { true },
    private val onRemoteFailure: () -> Unit = {},
) : AudioPolicyPort {

    override fun getForceUse(usage: Int): Int {
        requireForSystem(usage)
        return remoteCall { service.getForSystem() }
    }

    override fun setForceUse(usage: Int, config: Int) {
        requireForSystem(usage)
        require(
            config == AudioPolicyConstants.CONFIG_FORCE_NONE ||
                config == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
        ) {
            "Only FORCE_NONE and FORCE_SYSTEM_ENFORCED are allowed"
        }
        remoteCall { service.setForSystem(config) }
    }

    private fun requireForSystem(usage: Int) {
        require(usage == AudioPolicyConstants.USAGE_FOR_SYSTEM) {
            "Only FOR_SYSTEM is allowed"
        }
    }

    private inline fun <T> remoteCall(block: () -> T): T {
        check(isValid()) { "Audio policy UserService connection is no longer valid" }
        val result = try {
            block()
        } catch (e: Exception) {
            runCatching { onRemoteFailure() }
            throw e
        }
        check(isValid()) { "Audio policy UserService connection changed during the call" }
        return result
    }
}
