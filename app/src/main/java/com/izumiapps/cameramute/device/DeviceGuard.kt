package com.izumiapps.cameramute.device

import android.os.Build

/** Play版の同じ音設定方式で実機確認した (model, display, fingerprint) の組。 */
data class SupportedDevice(val model: String, val display: String, val fingerprint: String? = null)

/**
 * Play版で無音化・復元の実音確認を行ったモデルとビルド。ソース公開版の検証済み一覧ではない。
 * 他機種・OTA後は未検証として扱い、設定値の取得成功だけでここへ追加しない。
 * 現方式の確認範囲は実機試験記録に限定する。raw Binder番号の一般化は禁止。
 */
object VerifiedDevices {
    /** OPPO Find X9 日本版。Play版で無音化・復元を確認したビルド。 */
    val findX9Jp = SupportedDevice(
        model = "CPH2797",
        display = "CPH2797_16.0.10.501(EX01)",
        // 新方式のオン・復元と実音、権限取消・Shizuku停止・再接続を確認した識別情報。
        fingerprint = "OPPO/CPH2797/OP5E8BL1:16/BP2A.250605.015/B.R4T3_JP.d15960_182c05d_17ca7dc:user/release-keys",
    )

    /** OPPO Reno13 A。Play版の同じ音設定方式で動作確認したビルド。 */
    val reno13A = SupportedDevice(
        model = "A501OP",
        display = "A501OP_16.0.5.1001(EX01)",
        // 新方式のオン・復元と実音を開発者が実機で確認した識別情報。
        fingerprint = "OPPO/A501OP/OP5EC7L1:16/BP2A.250605.015/V.R4T2.1ed97e5_552621_52974b:user/release-keys",
    )

    val all: List<SupportedDevice> = listOf(
        findX9Jp,
        reno13A,
    )
}

data class DeviceInfo(val model: String, val display: String)

/**
 * 検証実績と復元記録の識別情報を保持する。利用可否を機種情報で制限しない。
 * 実際の操作には名前で解決した端末側API、復元記録、Controllerの同意が必要。
 * Android 15以上の条件はmanifestのminSdkでも強制する。
 */
class DeviceGuard(
    private val currentModel: String = Build.MODEL,
    private val currentDisplay: String = Build.DISPLAY,
    private val verifiedDevices: List<SupportedDevice> = VerifiedDevices.all,
    private val currentManufacturer: String = Build.MANUFACTURER.orEmpty(),
    private val currentBrand: String = Build.BRAND.orEmpty(),
    private val currentFingerprint: String = Build.FINGERPRINT.orEmpty(),
) {
    val current: DeviceInfo
        get() = DeviceInfo(currentModel, currentDisplay)

    val isVerified: Boolean
        get() = verifiedDevices.any {
            !it.fingerprint.isNullOrBlank() && it.fingerprint != Build.UNKNOWN &&
                it.model == currentModel && it.display == currentDisplay && it.fingerprint == currentFingerprint
        }

    val isUnverified: Boolean
        get() = !isVerified

    /** 同意・実音確認・復元記録をOTAや操作方式の変更後へ引き継がない。 */
    val safetyKey: String
        get() = "named-v1|$currentManufacturer|$currentBrand|$currentModel|$currentDisplay|$currentFingerprint"
}
