package com.izumiapps.cameramute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.izumiapps.cameramute.CameraSoundAssistApp
import com.izumiapps.cameramute.assist.ManualAssistController
import com.izumiapps.cameramute.device.DeviceGuard
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.shizuku.ShizukuGateway
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val controller: ManualAssistController,
    private val shizukuGateway: ShizukuGateway,
    private val deviceGuard: DeviceGuard,
) : ViewModel() {
    private val pendingAssistOperations = AtomicInteger(0)
    private val mutableState = MutableStateFlow(buildUiState())
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { publishCurrentState() }
        }
        viewModelScope.launch {
            shizukuGateway.connectionState.collect {
                if (it == ShizukuConnectionState.READY) {
                    runAssistOperation { controller.refresh() }
                } else {
                    publishCurrentState()
                }
            }
        }
    }

    fun refresh() = runAssistOperation { controller.refresh() }
    fun enableAssist() = runAssistOperation { controller.enable() }
    fun restoreGenuine() = runAssistOperation { controller.restore() }
    fun acceptSafety() = runAssistOperation { controller.acceptSafety() }
    fun requestShizukuPermission() { shizukuGateway.requestPermission(42) }

    private fun runAssistOperation(block: suspend () -> Unit) {
        pendingAssistOperations.incrementAndGet()
        publishCurrentState()
        viewModelScope.launch {
            try {
                block()
            } finally {
                pendingAssistOperations.decrementAndGet()
                publishCurrentState()
            }
        }
    }

    /** 各Flowの最新値を読み直し、遅れて届いた古いcombine結果をUIへ出さない。 */
    private fun publishCurrentState() {
        mutableState.value = buildUiState()
    }

    private fun buildUiState(): MainUiState {
        val assist = controller.state.value
        val connection = shizukuGateway.connectionState.value
        val operationPending = pendingAssistOperations.get() > 0
        val assistBusy = operationPending || assist.isBusy
        return MainUiState(
            shizukuState = connection,
            deviceUnverified = deviceGuard.isUnverified,
            deviceModel = deviceGuard.current.model,
            deviceDisplay = deviceGuard.current.display,
            forceUseState = assist.forceUseState.takeIf {
                connection == ShizukuConnectionState.READY && !assistBusy
            },
            isBusy = assistBusy,
            notificationPermissionGranted = assist.notificationAvailable,
            consentAccepted = assist.consentAccepted,
            silentOrVibrate = assist.silentOrVibrate,
            lastMessage = assist.lastMessage.takeUnless { assistBusy },
        )
    }

    companion object {
        fun factory(app: CameraSoundAssistApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    app.manualAssistController,
                    app.shizukuGateway,
                    app.deviceGuard,
                )
            }
        }
    }
}
