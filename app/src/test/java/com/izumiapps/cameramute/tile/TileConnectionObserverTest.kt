package com.izumiapps.cameramute.tile

import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TileConnectionObserverTest {
    @Test
    fun initialReadyRefreshesOnce() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.READY)
        var refreshCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady { refreshCount += 1 }
        }

        assertEquals(1, refreshCount)
    }

    @Test
    fun readyArrivingAfterListeningRefreshesOnce() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.NOT_AVAILABLE)
        var refreshCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady { refreshCount += 1 }
        }

        assertEquals(0, refreshCount)
        connection.value = ShizukuConnectionState.PERMISSION_DENIED
        assertEquals(0, refreshCount)
        connection.value = ShizukuConnectionState.READY
        assertEquals(1, refreshCount)
    }

    @Test
    fun eachNewReadyTransitionRefreshesOnce() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.READY)
        var refreshCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady { refreshCount += 1 }
        }

        connection.value = ShizukuConnectionState.NOT_AVAILABLE
        connection.value = ShizukuConnectionState.READY
        connection.value = ShizukuConnectionState.PERMISSION_DENIED
        connection.value = ShizukuConnectionState.READY

        assertEquals(3, refreshCount)
    }

    @Test
    fun repeatedReadyEmissionsDoNotRefreshAgain() = runTest {
        val connection = MutableSharedFlow<ShizukuConnectionState>()
        var refreshCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady { refreshCount += 1 }
        }

        connection.emit(ShizukuConnectionState.READY)
        connection.emit(ShizukuConnectionState.READY)
        connection.emit(ShizukuConnectionState.NOT_AVAILABLE)
        connection.emit(ShizukuConnectionState.NOT_AVAILABLE)
        connection.emit(ShizukuConnectionState.READY)
        connection.emit(ShizukuConnectionState.READY)

        assertEquals(2, refreshCount)
    }

    @Test
    fun reconnectDuringSlowRefreshQueuesAnotherRefresh() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.READY)
        val firstRefreshMayFinish = CompletableDeferred<Unit>()
        var refreshRequests = 0
        var completedRefreshes = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady {
                refreshRequests += 1
                if (refreshRequests == 1) firstRefreshMayFinish.await()
                completedRefreshes += 1
            }
        }

        assertEquals(1, refreshRequests)
        assertEquals(0, completedRefreshes)
        connection.value = ShizukuConnectionState.NOT_AVAILABLE
        connection.value = ShizukuConnectionState.READY

        assertEquals(1, refreshRequests)
        assertEquals(0, completedRefreshes)
        firstRefreshMayFinish.complete(Unit)
        assertEquals(2, refreshRequests)
        assertEquals(2, completedRefreshes)
    }

    @Test
    fun manyReconnectsDuringSlowRefreshKeepOnlyOnePendingRefresh() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.READY)
        val firstRefreshMayFinish = CompletableDeferred<Unit>()
        var refreshRequests = 0
        var activeRefreshes = 0
        var maxActiveRefreshes = 0
        var completedRefreshes = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady {
                refreshRequests += 1
                activeRefreshes += 1
                maxActiveRefreshes = maxOf(maxActiveRefreshes, activeRefreshes)
                try {
                    if (refreshRequests == 1) firstRefreshMayFinish.await()
                    completedRefreshes += 1
                } finally {
                    activeRefreshes -= 1
                }
            }
        }

        repeat(100) {
            connection.value = ShizukuConnectionState.NOT_AVAILABLE
            connection.value = ShizukuConnectionState.READY
        }

        assertEquals(1, refreshRequests)
        assertEquals(1, activeRefreshes)
        assertEquals(0, completedRefreshes)
        firstRefreshMayFinish.complete(Unit)
        assertEquals(2, refreshRequests)
        assertEquals(2, completedRefreshes)
        assertEquals(0, activeRefreshes)
        assertEquals(1, maxActiveRefreshes)
    }

    @Test
    fun cancelledListenerDoesNotRefreshForLaterReady() = runTest {
        val connection = MutableStateFlow(ShizukuConnectionState.NOT_AVAILABLE)
        var refreshCount = 0
        val listener = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.refreshOnReady { refreshCount += 1 }
        }

        listener.cancel()
        connection.value = ShizukuConnectionState.READY

        assertEquals(0, refreshCount)
    }
}
