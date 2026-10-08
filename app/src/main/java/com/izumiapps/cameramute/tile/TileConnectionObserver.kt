package com.izumiapps.cameramute.tile

import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** 更新の契機をコントローラーの状態更新から独立させる。 */
internal suspend fun Flow<ShizukuConnectionState>.refreshOnReady(
    refresh: suspend () -> Unit,
) {
    distinctUntilChanged()
        .filter { it == ShizukuConnectionState.READY }
        .map { Unit }
        // 更新中も監視を続け、待機中の再接続は最大1件だけ保持する。
        .buffer(Channel.CONFLATED)
        .collect { refresh() }
}
