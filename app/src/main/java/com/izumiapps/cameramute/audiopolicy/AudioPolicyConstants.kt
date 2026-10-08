package com.izumiapps.cameramute.audiopolicy

/**
 * アプリが扱える値の契約。UserServiceは端末のAudioSystem定数を名前で取得し、
 * これらと一致することを操作前に照合する。OSのBinder番号は固定・推測しない。
 * 11がどの端末でも元の値だとは仮定せず、未検証端末では変更前の11を記録する。
 */
object AudioPolicyConstants {
    /** audio_policy_force_use_t の AUDIO_POLICY_FORCE_FOR_SYSTEM。 */
    const val USAGE_FOR_SYSTEM = 4

    /** audio_policy_forced_cfg_t の AUDIO_POLICY_FORCE_NONE。 */
    const val CONFIG_FORCE_NONE = 0

    /** audio_policy_forced_cfg_t の AUDIO_POLICY_FORCE_SYSTEM_ENFORCED (純正状態)。 */
    const val CONFIG_FORCE_SYSTEM_ENFORCED = 11
}
