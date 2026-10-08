package com.izumiapps.cameramute.tile

import android.service.quicksettings.Tile
import com.izumiapps.cameramute.R
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState

/** タイルの状態と補足表示の文字列リソース。 */
internal data class TileAppearance(val state: Int, val subtitleRes: Int)

/**
 * 未対応の起動方式は保持中の音設定より優先して伝える。
 * それ以外で接続・取得が確定していない場合は状態不明として扱う。
 */
internal fun tileAppearance(
    connection: ShizukuConnectionState,
    forceUseState: ForceUseState?,
    isBusy: Boolean,
): TileAppearance {
    if (connection == ShizukuConnectionState.UNSUPPORTED_BACKEND) {
        return TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_shizuku_unsupported_backend)
    }
    val current = forceUseState.takeIf { !isBusy && connection == ShizukuConnectionState.READY }
    return when (current) {
        ForceUseState.GenuineEnforced -> TileAppearance(Tile.STATE_INACTIVE, R.string.state_genuine)
        ForceUseState.MuteAssistActive -> TileAppearance(Tile.STATE_ACTIVE, R.string.state_mute_assist)
        ForceUseState.UnmanagedUnforced -> TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unmanaged_unforced)
        is ForceUseState.Unexpected -> TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unexpected_value)
        null -> TileAppearance(Tile.STATE_UNAVAILABLE, R.string.state_unknown)
    }
}
