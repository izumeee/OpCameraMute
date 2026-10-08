package com.izumiapps.cameramute.audiopolicy

/** `getForceUse(FOR_SYSTEM)` の戻り値を解釈した状態。0/11以外は未知の値として扱う。 */
sealed class ForceUseState {
    /** 純正状態: 強制可聴 (config=11)。 */
    data object GenuineEnforced : ForceUseState()

    /** 無音補助中 (config=0)。 */
    data object MuteAssistActive : ForceUseState()

    /** 未検証端末の0で変更記録がない。元の値を推測して11にしてはいけない。 */
    data object UnmanagedUnforced : ForceUseState()

    /** 0/11以外の想定外の値。安全のため、この状態からの変更は一律拒否する。 */
    data class Unexpected(val rawValue: Int) : ForceUseState()

    companion object {
        fun fromRaw(rawValue: Int): ForceUseState = when (rawValue) {
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED -> GenuineEnforced
            AudioPolicyConstants.CONFIG_FORCE_NONE -> MuteAssistActive
            else -> Unexpected(rawValue)
        }
    }
}
