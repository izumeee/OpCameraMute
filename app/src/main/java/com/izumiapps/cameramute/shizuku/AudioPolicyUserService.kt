package com.izumiapps.cameramute.shizuku

import androidx.annotation.Keep
import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import com.izumiapps.cameramute.audiopolicy.ForSystemAudioPolicy
import com.izumiapps.cameramute.audiopolicy.NamedAudioSystemAdapter
import kotlin.system.exitProcess

/**
 * shell UIDのShizuku UserService内で端末自身のAudioSystemを呼ぶ最小サービス。
 * 生成・接続だけでは読み取りも書き込みも行わない。
 *
 * 名前付き端末APIを解決できない場合は [NamedAudioSystemAdapter] が例外を返す。
 * UserService上でのhidden API/JNI経路をraw Binderへフォールバックしない。
 */
@Keep
class AudioPolicyUserService internal constructor(
    private val audioPolicy: ForSystemAudioPolicy,
) : IAudioPolicyUserService.Stub() {

    /** Shizukuが反射で生成するために必要な公開no-arg constructor。 */
    @Keep
    constructor() : this(NamedAudioSystemAdapter())

    override fun getForSystem(): Int = audioPolicy.getForSystem()

    override fun setForSystem(config: Int) {
        require(
            config == AudioPolicyConstants.CONFIG_FORCE_NONE ||
                config == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
        ) {
            "Only FORCE_NONE and FORCE_SYSTEM_ENFORCED are allowed"
        }
        audioPolicy.setForSystem(config)
    }

    /** Shizuku UserServiceの予約済み停止メソッド。 */
    override fun destroy(): Unit = exitProcess(0)
}
