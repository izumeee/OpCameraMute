package com.izumiapps.cameramute.audiopolicy

import android.content.SharedPreferences

/** setterより先に同期保存し、復元の読取検証後だけ消す。破損・保存失敗は例外で通知する。 */
interface AudioRecoveryStore {
    fun load(): Int?
    fun save(original: Int)
    fun clear()
}

class PreferencesAudioRecoveryStore(
    private val prefs: SharedPreferences,
    deviceKey: String,
    private val bootCount: () -> Int,
) : AudioRecoveryStore {
    private val key = "$deviceKey:original"
    private val bootKey = "$deviceKey:boot"

    override fun load(): Int? {
        if (!prefs.contains(key)) return null
        val currentBoot = bootCount()
        check(currentBoot >= 0) { "端末の起動回数を確認できません" }
        val savedBoot = prefs.getInt(bootKey, -1)
        check(savedBoot >= 0) { "復元記録の起動回数を確認できません" }
        if (savedBoot != currentBoot) {
            clear()
            return null
        }
        return prefs.getInt(key, -1)
    }

    override fun save(original: Int) {
        require(original == AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED)
        val currentBoot = bootCount()
        check(currentBoot >= 0) { "端末の起動回数を確認できません" }
        check(prefs.edit().putInt(key, original).putInt(bootKey, currentBoot).commit()) {
            "変更前の音設定を保存できません"
        }
    }

    override fun clear() {
        check(prefs.edit().remove(key).remove(bootKey).commit()) { "復元記録を更新できません" }
    }
}
