package com.izumiapps.cameramute.assist

import android.content.Context

/** 同意は端末/ビルドごとに扱う。 */
interface AssistSafetyStore {
    var consentAccepted: Boolean
}

class PreferencesAssistSafetyStore(context: Context, deviceKey: String) : AssistSafetyStore {
    private val prefs = context.getSharedPreferences("manual_assist_safety_v1", Context.MODE_PRIVATE)
    private val prefix = "$deviceKey:"

    private fun read(name: String): Boolean = try {
        prefs.getBoolean(prefix + name, false)
    } catch (_: ClassCastException) {
        false
    }
    private fun write(name: String, value: Boolean) {
        check(prefs.edit().putBoolean(prefix + name, value).commit()) { "同意状態の保存に失敗しました" }
    }

    override var consentAccepted: Boolean
        get() = read("consent")
        set(value) = write("consent", value)
}
