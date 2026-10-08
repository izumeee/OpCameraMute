package com.izumiapps.cameramute.assist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import com.izumiapps.cameramute.audiopolicy.AudioPolicyPort
import com.izumiapps.cameramute.audiopolicy.AudioPolicyRepository
import com.izumiapps.cameramute.audiopolicy.AudioRecoveryStore
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.audiopolicy.MemoryAudioRecoveryStore
import com.izumiapps.cameramute.device.DeviceGuard
import com.izumiapps.cameramute.device.VerifiedTestDevice
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.shizuku.ShizukuGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(InternalCoroutinesApi::class)
private class PausableTestDispatcher(private val delegate: TestDispatcher) :
    CoroutineDispatcher(), Delay by delegate {
    var holdDispatches = false
    private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holdDispatches) held += context to block
        else delegate.dispatch(context, block)
    }

    fun resumeDispatches() {
        holdDispatches = false
        held.toList().also { held.clear() }.forEach { (context, block) ->
            delegate.dispatch(context, block)
        }
    }
}

private class ControllerFakeAudioPolicyPort(initialValue: Int) : AudioPolicyPort {
    var value = initialValue
    var readCount = 0
    var failRead = false
    var failEnable = false
    var failRestore = false
    var ignoreEnable = false
    var enableWriteCount = 0
    var restoreWriteCount = 0
    var onSet: ((Int) -> Unit)? = null

    override fun getForceUse(usage: Int): Int {
        readCount += 1
        check(!failRead) { "simulated read failure" }
        return value
    }

    override fun setForceUse(usage: Int, config: Int) {
        onSet?.invoke(config)
        when (config) {
            AudioPolicyConstants.CONFIG_FORCE_NONE -> {
                enableWriteCount += 1
                check(!failEnable) { "simulated enable failure" }
                if (!ignoreEnable) value = config
            }
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED -> {
                restoreWriteCount += 1
                check(!failRestore) { "simulated restore failure" }
                value = config
            }
        }
    }
}

private class ControllerFakeShizukuGateway(
    initialState: ShizukuConnectionState = ShizukuConnectionState.READY,
) : ShizukuGateway {
    override val connectionState = MutableStateFlow(initialState)

    override fun requestPermission(requestCode: Int) = Unit

    override suspend fun obtainAudioPolicyPort(): AudioPolicyPort =
        error("portProviderからfake portを渡すため呼ばれない")
}

private class ControllerSafetyStore(
    override var consentAccepted: Boolean = true,
) : AssistSafetyStore

private class ControllerNotifications(
    var available: Boolean = true,
    var manualResult: Boolean = true,
    var throwOnManual: Boolean = false,
) : AssistNotifications {
    var manualCount = 0
    var cancelCount = 0
    var throwOnCancel = false
    var restoreFailedCount = 0
    var restoreFailureVisible = false

    override fun canShowStatus(): Boolean = available

    override fun showManualActive(): Boolean {
        manualCount += 1
        check(!throwOnManual) { "simulated notification failure" }
        return manualResult
    }

    override fun cancelAll() {
        cancelCount += 1
        check(!throwOnCancel) { "simulated notification cancellation failure" }
        restoreFailureVisible = false
    }

    override fun showRestoreFailed() {
        restoreFailedCount += 1
        restoreFailureVisible = true
    }
}

private data class ControllerFixture(
    val controller: ManualAssistController,
    val gateway: ControllerFakeShizukuGateway,
    val port: ControllerFakeAudioPolicyPort,
    val safety: ControllerSafetyStore,
    val notifications: ControllerNotifications,
)

private fun controllerFixture(
    dispatcher: CoroutineDispatcher,
    initialForceUse: Int = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
    gateway: ControllerFakeShizukuGateway = ControllerFakeShizukuGateway(),
    beforePort: suspend () -> Unit = {},
    safety: ControllerSafetyStore = ControllerSafetyStore(),
    notifications: ControllerNotifications = ControllerNotifications(),
    isUnverifiedDevice: Boolean = false,
    recoveryOriginal: Int? = null,
    recoveryStore: AudioRecoveryStore = MemoryAudioRecoveryStore(recoveryOriginal),
    silentOrVibrate: () -> Boolean = { true },
): ControllerFixture {
    val port = ControllerFakeAudioPolicyPort(initialForceUse)
    val guard = if (isUnverifiedDevice) {
        DeviceGuard(
            currentModel = "unverified",
            currentDisplay = "build",
            currentFingerprint = "test-unverified",
            currentManufacturer = "OPPO",
            currentBrand = "OPPO",
        )
    } else {
        VerifiedTestDevice.guard()
    }
    val repository = AudioPolicyRepository(
        shizukuGateway = gateway,
        deviceGuard = guard,
        recoveryStore = recoveryStore,
        ioDispatcher = dispatcher,
        portProvider = { beforePort(); port },
    )
    val controller = ManualAssistController(
        repository = repository,
        safety = safety,
        notifications = notifications,
        isSilentOrVibrate = silentOrVibrate,
        resources = ApplicationProvider.getApplicationContext<Context>().resources,
    )
    return ControllerFixture(
        controller = controller,
        gateway = gateway,
        port = port,
        safety = safety,
        notifications = notifications,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ManualAssistControllerTest {

    @Test
    fun `safety consent is persisted independently for each device build`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manual_assist_safety_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val firstBuild = PreferencesAssistSafetyStore(context, "model/build-a")
        val secondBuild = PreferencesAssistSafetyStore(context, "model/build-b")
        assertFalse(firstBuild.consentAccepted)
        assertFalse(secondBuild.consentAccepted)

        firstBuild.consentAccepted = true

        assertTrue(PreferencesAssistSafetyStore(context, "model/build-a").consentAccepted)
        assertFalse(secondBuild.consentAccepted)
    }

    @Test
    fun `unverified device can enable restore and reenable with safety consent`() = runTest {
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            isUnverifiedDevice = true,
        )

        assertTrue(fixture.controller.enable())
        assertEquals(1, fixture.port.enableWriteCount)
        assertTrue(fixture.controller.restore())
        assertTrue(fixture.controller.toggle())
        assertEquals(2, fixture.port.enableWriteCount)
    }

    @Test
    fun `unmanaged zero does not allow toggle or claim active state`() = runTest {
        val safety = ControllerSafetyStore()
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            safety = safety,
            isUnverifiedDevice = true,
        )

        assertFalse(fixture.controller.toggle())

        assertEquals(0, fixture.port.enableWriteCount)
        assertEquals(0, fixture.port.restoreWriteCount)
        assertEquals(ForceUseState.UnmanagedUnforced, fixture.controller.state.value.forceUseState)
        val message = fixture.controller.state.value.lastMessage.orEmpty()
        assertTrue(message.contains("元々この設定の端末では操作は不要です"))
        assertTrue(message.contains("他のアプリで変更した場合はそのアプリで戻し"))
        assertTrue(message.contains("元の値を推測して変更することはありません"))
        assertTrue(message.contains("本アプリで変更した後に記録を失った場合は、端末を再起動して設定と実際の音を確認してください"))
        assertTrue(message.contains("再起動で元に戻るとは限りません"))
    }

    @Test
    fun `failed enable does not post an active notification`() = runTest {
        val fixture = controllerFixture(UnconfinedTestDispatcher(testScheduler))
        fixture.port.failEnable = true

        assertFalse(fixture.controller.enable())

        assertEquals(0, fixture.notifications.manualCount)
    }

    @Test
    fun `post-write verification mismatch does not claim activation`() = runTest {
        val fixture = controllerFixture(UnconfinedTestDispatcher(testScheduler))
        fixture.port.ignoreEnable = true

        assertFalse(fixture.controller.enable())

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(0, fixture.notifications.manualCount)
    }

    @Test
    fun `consent is required before enabling`() = runTest {
        val safety = ControllerSafetyStore(consentAccepted = false)
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            safety = safety,
        )

        assertFalse(fixture.controller.enable())

        assertEquals(0, fixture.port.enableWriteCount)
    }

    @Test
    fun `status notification availability is required before enabling`() = runTest {
        val notifications = ControllerNotifications(available = false)
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            notifications = notifications,
        )

        assertFalse(fixture.controller.enable())

        assertEquals(0, fixture.port.enableWriteCount)
        assertEquals(0, notifications.manualCount)
    }

    @Test
    fun `silent or vibrate mode is required before enabling`() = runTest {
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            silentOrVibrate = { false },
        )

        assertFalse(fixture.controller.enable())

        assertEquals(0, fixture.port.enableWriteCount)
    }

    @Test
    fun `unverified restore works even when every enable gate is closed`() = runTest {
        val safety = ControllerSafetyStore(
            consentAccepted = false,
        )
        val notifications = ControllerNotifications(available = false)
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            safety = safety,
            notifications = notifications,
            isUnverifiedDevice = true,
            recoveryOriginal = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            silentOrVibrate = { false },
        )

        assertTrue(fixture.controller.restore())

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertEquals(1, notifications.cancelCount)
    }

    @Test
    fun `refresh shows the active notification while force use is zero`() = runTest {
        val notifications = ControllerNotifications()
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            notifications = notifications,
        )

        assertTrue(fixture.controller.refresh())

        assertEquals(1, notifications.manualCount)
        assertEquals(0, notifications.cancelCount)
        assertEquals(ForceUseState.MuteAssistActive, fixture.controller.state.value.forceUseState)
    }

    @Test
    fun `refresh clears notifications only after force use eleven is verified`() = runTest {
        val notifications = ControllerNotifications()
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            notifications = notifications,
        )

        assertTrue(fixture.controller.refresh())

        assertEquals(1, notifications.cancelCount)
        assertEquals(ForceUseState.GenuineEnforced, fixture.controller.state.value.forceUseState)
    }

    @Test
    fun `failed restore retains notifications and reports restore failure`() = runTest {
        val notifications = ControllerNotifications()
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            notifications = notifications,
        )
        fixture.port.failRestore = true

        assertFalse(fixture.controller.restore())

        assertEquals(0, notifications.cancelCount)
        assertEquals(1, notifications.restoreFailedCount)
    }

    @Test
    fun `notification failure rolls back force use`() = runTest {
        val notifications = ControllerNotifications(manualResult = false)
        val fixture = controllerFixture(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            notifications = notifications,
        )

        assertFalse(fixture.controller.enable())

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertTrue(fixture.controller.state.value.lastMessage.orEmpty().contains("元の音設定へ戻しました"))
    }

    @Test
    fun `concurrent enable requests serialize and write force none only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = controllerFixture(dispatcher)

        val first = async(dispatcher) { fixture.controller.enable() }
        val second = async(dispatcher) { fixture.controller.enable() }
        advanceUntilIdle()

        assertEquals(1, listOf(first.await(), second.await()).count { it })
        assertEquals(1, fixture.port.enableWriteCount)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
    }

    @Test
    fun `caller cancellation after setter starts cannot interrupt verification or notification`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = controllerFixture(dispatcher)
        lateinit var request: kotlinx.coroutines.Deferred<Boolean>
        fixture.port.onSet = { config ->
            if (config == AudioPolicyConstants.CONFIG_FORCE_NONE) request.cancel()
        }

        request = async(dispatcher) { fixture.controller.enable() }
        advanceUntilIdle()

        assertTrue(request.isCancelled)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
        assertEquals(1, fixture.notifications.manualCount)
        assertFalse(fixture.controller.state.value.isBusy)
    }

    @Test
    fun `tile can enable immediately once safety conditions are met`() = runTest {
        val fixture = controllerFixture(UnconfinedTestDispatcher(testScheduler))

        assertTrue(fixture.controller.toggle())

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
        assertEquals(1, fixture.port.enableWriteCount)
        assertEquals(1, fixture.notifications.manualCount)
    }

    @Test
    fun `tile preserves consent notification and silent gates`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fixtures = listOf(
            controllerFixture(dispatcher, safety = ControllerSafetyStore(consentAccepted = false)),
            controllerFixture(dispatcher, notifications = ControllerNotifications(available = false)),
            controllerFixture(dispatcher, silentOrVibrate = { false }),
        )

        fixtures.forEach { fixture ->
            assertFalse(fixture.controller.toggle())
            assertEquals(0, fixture.port.enableWriteCount)
        }
    }

    @Test
    fun `unsupported Shizuku backend rejects enable restore and toggle before audio access`() = runTest {
        val fixture = controllerFixture(
            UnconfinedTestDispatcher(testScheduler),
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.UNSUPPORTED_BACKEND),
        )
        val operations = listOf<suspend () -> Boolean>(
            fixture.controller::enable,
            fixture.controller::restore,
            fixture.controller::toggle,
        )

        for (operation in operations) {
            assertFalse(operation())

            val message = fixture.controller.state.value.lastMessage.orEmpty()
            assertTrue(message.contains("root方式"))
            assertTrue(message.contains("ワイヤレスデバッグまたはADB"))
            assertNull(fixture.controller.state.value.forceUseState)
            assertFalse(fixture.controller.state.value.isBusy)
            assertEquals(0, fixture.port.readCount)
            assertEquals(0, fixture.port.enableWriteCount)
            assertEquals(0, fixture.port.restoreWriteCount)
            assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
            assertEquals(0, fixture.notifications.manualCount)
        }
    }

    @Test
    fun `read failure leaves state unknown and prevents both enable entry points`() = runTest {
        val fixture = controllerFixture(UnconfinedTestDispatcher(testScheduler))
        fixture.port.failRead = true

        assertFalse(fixture.controller.enable())
        assertFalse(fixture.controller.toggle())

        assertNull(fixture.controller.state.value.forceUseState)
        assertFalse(fixture.controller.state.value.isBusy)
        assertEquals(0, fixture.port.enableWriteCount)
    }

    @Test
    fun `unexpected audio state is never overwritten`() = runTest {
        val fixture = controllerFixture(UnconfinedTestDispatcher(testScheduler), initialForceUse = 99)

        assertFalse(fixture.controller.enable())
        assertFalse(fixture.controller.toggle())
        assertFalse(fixture.controller.restore())

        assertEquals(99, fixture.port.value)
        assertEquals(0, fixture.port.enableWriteCount)
        assertEquals(0, fixture.port.restoreWriteCount)
    }

    @Test
    fun `unmanaged zero remains protected from enable toggle and restore`() = runTest {
        val fixture = controllerFixture(
            UnconfinedTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            isUnverifiedDevice = true,
        )

        assertFalse(fixture.controller.enable())
        assertFalse(fixture.controller.toggle())
        assertFalse(fixture.controller.restore())

        assertEquals(0, fixture.port.enableWriteCount)
        assertEquals(0, fixture.port.restoreWriteCount)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
    }

    @Test
    fun `recovery persistence failure stops before the setter`() = runTest {
        val recoveryStore = object : AudioRecoveryStore {
            override fun load(): Int? = null
            override fun save(original: Int) = error("simulated storage failure")
            override fun clear() = Unit
        }
        val fixture = controllerFixture(
            UnconfinedTestDispatcher(testScheduler),
            recoveryStore = recoveryStore,
        )

        assertFalse(fixture.controller.enable())

        assertEquals(0, fixture.port.enableWriteCount)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(1, fixture.notifications.restoreFailedCount)
    }

    @Test
    fun `notification exception rolls back the successful audio change`() = runTest {
        val fixture = controllerFixture(
            UnconfinedTestDispatcher(testScheduler),
            notifications = ControllerNotifications(throwOnManual = true),
        )

        assertFalse(fixture.controller.enable())

        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertFalse(fixture.controller.state.value.isBusy)
        assertTrue(fixture.controller.state.value.lastMessage.orEmpty().contains("元の音設定へ戻しました"))
    }

    @Test
    fun `failed rollback reports unknown state and allows a later restore`() = runTest {
        val fixture = controllerFixture(
            UnconfinedTestDispatcher(testScheduler),
            notifications = ControllerNotifications(manualResult = false),
        )
        fixture.port.failRestore = true

        assertFalse(fixture.controller.enable())

        assertNull(fixture.controller.state.value.forceUseState)
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
        assertFalse(fixture.controller.state.value.isBusy)
        assertTrue(fixture.controller.state.value.lastMessage.orEmpty().contains("復元も確認できません"))

        fixture.port.failRestore = false
        assertTrue(fixture.controller.restore())
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
    }

    @Test
    fun `notification restore waits for the cold start Binder and restores once`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
        )
        val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        runCurrent()
        assertEquals(0, fixture.port.readCount)
        advanceTimeBy(1_000)
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()

        assertTrue(request.await())
        assertEquals(1, fixture.port.restoreWriteCount)
        assertEquals(0, fixture.notifications.restoreFailedCount)
        assertEquals(ForceUseState.GenuineEnforced, fixture.controller.state.value.forceUseState)
    }

    @Test
    fun `notification restore stops waiting when permission is denied`() = runTest {
        for (initial in listOf(ShizukuConnectionState.NOT_AVAILABLE, ShizukuConnectionState.PERMISSION_DENIED)) {
            val fixture = controllerFixture(
                StandardTestDispatcher(testScheduler),
                initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
                gateway = ControllerFakeShizukuGateway(initial),
            )
            val startedAt = currentTime
            val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
            runCurrent()
            if (initial == ShizukuConnectionState.NOT_AVAILABLE) {
                advanceTimeBy(500)
                fixture.gateway.connectionState.value = ShizukuConnectionState.PERMISSION_DENIED
            }
            advanceUntilIdle()

            assertFalse(request.await())
            assertTrue(currentTime - startedAt <= 500)
            assertEquals(0, fixture.port.readCount)
            assertEquals(0, fixture.port.restoreWriteCount)
            assertEquals(1, fixture.notifications.restoreFailedCount)
            assertTrue(fixture.controller.state.value.lastMessage.orEmpty().contains("権限"))
        }
    }

    @Test
    fun `notification restore fails immediately when unsupported backend is detected`() = runTest {
        for (initial in listOf(ShizukuConnectionState.NOT_AVAILABLE, ShizukuConnectionState.UNSUPPORTED_BACKEND)) {
            val fixture = controllerFixture(
                StandardTestDispatcher(testScheduler),
                initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
                gateway = ControllerFakeShizukuGateway(initial),
            )
            val startedAt = currentTime
            val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
            runCurrent()
            val expectedWait = if (initial == ShizukuConnectionState.NOT_AVAILABLE) {
                assertFalse(request.isCompleted)
                advanceTimeBy(500)
                fixture.gateway.connectionState.value = ShizukuConnectionState.UNSUPPORTED_BACKEND
                runCurrent()
                500L
            } else {
                0L
            }

            assertTrue(request.isCompleted)
            assertFalse(request.await())
            assertEquals(expectedWait, currentTime - startedAt)
            assertEquals(0, fixture.port.readCount)
            assertEquals(0, fixture.port.enableWriteCount)
            assertEquals(0, fixture.port.restoreWriteCount)
            assertEquals(AudioPolicyConstants.CONFIG_FORCE_NONE, fixture.port.value)
            assertEquals(1, fixture.notifications.restoreFailedCount)
            assertTrue(fixture.notifications.restoreFailureVisible)
            assertEquals(0, fixture.notifications.cancelCount)
            assertFalse(fixture.controller.state.value.isBusy)
            val message = fixture.controller.state.value.lastMessage.orEmpty()
            assertTrue(message.contains("root方式"))
            assertTrue(message.contains("ワイヤレスデバッグまたはADB"))
        }
    }

    @Test
    fun `notification restore times out disconnected and ignores later READY`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
        )
        val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        advanceUntilIdle()

        assertFalse(request.await())
        assertEquals(2_000L, currentTime)
        assertEquals(1, fixture.notifications.restoreFailedCount)
        assertEquals(0, fixture.notifications.cancelCount)
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()
        assertEquals(0, fixture.port.readCount)
        assertEquals(0, fixture.port.restoreWriteCount)
    }

    @Test
    fun `notification restore reports bind timeout after delayed READY within seven seconds`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
            beforePort = { withTimeout(5_000) { awaitCancellation() } },
        )
        val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        runCurrent()
        advanceTimeBy(1_500)
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()

        assertFalse(request.await())
        assertEquals(6_500L, currentTime)
        assertEquals(1, fixture.notifications.restoreFailedCount)
        assertEquals(0, fixture.notifications.cancelCount)
        assertEquals(0, fixture.port.restoreWriteCount)
        assertFalse(fixture.controller.state.value.isBusy)
    }

    @Test
    fun `duplicate notification requests preserve verified success across the startup deadline`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
            beforePort = { delay(1_000) },
        )
        val first = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        val duplicate = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        runCurrent()
        advanceTimeBy(1_500)
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()

        assertTrue(first.await())
        assertFalse(duplicate.await())
        assertEquals(2_500L, currentTime)
        assertNull(fixture.controller.state.value.lastMessage)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertEquals(1, fixture.notifications.restoreFailedCount)
        assertEquals(1, fixture.notifications.cancelCount)
        assertEquals(ForceUseState.GenuineEnforced, fixture.controller.state.value.forceUseState)
        assertFalse(fixture.controller.state.value.isBusy)
    }

    @Test
    fun `simultaneous notification restores serialize and never write twice`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
        )
        val first = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        val second = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        runCurrent()
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, fixture.port.restoreWriteCount)
        assertEquals(0, fixture.notifications.restoreFailedCount)
    }

    @Test
    fun `cancelled notification wait releases the operation lock without changing audio`() = runTest {
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.NOT_AVAILABLE),
        )
        val request = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        runCurrent()
        request.cancel()
        runCurrent()
        fixture.gateway.connectionState.value = ShizukuConnectionState.READY
        advanceUntilIdle()
        assertTrue(request.isCancelled)
        assertEquals(0, fixture.port.restoreWriteCount)
        assertTrue(fixture.controller.restore())
        assertEquals(1, fixture.port.restoreWriteCount)
    }

    @Test
    fun `repository guard still rejects connection loss after notification readiness`() = runTest {
        val gateway = ControllerFakeShizukuGateway(ShizukuConnectionState.READY)
        val fixture = controllerFixture(
            StandardTestDispatcher(testScheduler),
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            gateway = gateway,
            beforePort = { gateway.connectionState.value = ShizukuConnectionState.NOT_AVAILABLE },
        )

        assertFalse(fixture.controller.restoreFromNotification(gateway.connectionState))
        assertEquals(0, fixture.port.readCount)
        assertEquals(0, fixture.port.restoreWriteCount)
        assertEquals(1, fixture.notifications.restoreFailedCount)
    }

    @Test
    fun `delayed timeout handling cannot post a stale failure after a concurrent restore succeeds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val duplicateDispatcher = PausableTestDispatcher(dispatcher)
        val fixture = controllerFixture(
            dispatcher,
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            beforePort = { delay(2_500) },
        )
        val first = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        val duplicate = async(duplicateDispatcher) {
            fixture.controller.restoreFromNotification(fixture.gateway.connectionState)
        }
        runCurrent()
        // The waiting request times out, but its IO continuation runs after the restore finishes.
        duplicateDispatcher.holdDispatches = true
        advanceUntilIdle()
        assertTrue(first.await())
        assertEquals(1, fixture.notifications.cancelCount)
        duplicateDispatcher.resumeDispatches()
        advanceUntilIdle()

        assertFalse(duplicate.await())
        assertEquals(2_500L, currentTime)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertFalse(fixture.notifications.restoreFailureVisible)
    }

    @Test
    fun `delayed timeout handling cannot overwrite a later refresh that verifies restored audio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val duplicateDispatcher = PausableTestDispatcher(dispatcher)
        val fixture = controllerFixture(dispatcher, beforePort = { delay(2_500) })
        val refresh = async { fixture.controller.refresh() }
        val request = async(duplicateDispatcher) {
            fixture.controller.restoreFromNotification(fixture.gateway.connectionState)
        }
        runCurrent()
        duplicateDispatcher.holdDispatches = true
        advanceUntilIdle()
        assertTrue(refresh.await())
        assertEquals(1, fixture.notifications.cancelCount)
        duplicateDispatcher.resumeDispatches()
        advanceUntilIdle()

        assertFalse(request.await())
        assertEquals(0, fixture.port.restoreWriteCount)
        assertFalse(fixture.notifications.restoreFailureVisible)
    }

    @Test
    fun `a new disconnected restore request still reports failure after an earlier verified restore`() = runTest {
        val fixture = controllerFixture(StandardTestDispatcher(testScheduler))
        assertTrue(fixture.controller.restore())
        fixture.gateway.connectionState.value = ShizukuConnectionState.NOT_AVAILABLE

        assertFalse(fixture.controller.restoreFromNotification(fixture.gateway.connectionState))
        assertEquals(1, fixture.notifications.restoreFailedCount)
        assertTrue(fixture.notifications.restoreFailureVisible)
    }

    @Test
    fun `notification cancellation failure does not let an older timeout overwrite verified audio restoration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val duplicateDispatcher = PausableTestDispatcher(dispatcher)
        val notifications = ControllerNotifications().apply { throwOnCancel = true }
        val fixture = controllerFixture(
            dispatcher,
            initialForceUse = AudioPolicyConstants.CONFIG_FORCE_NONE,
            notifications = notifications,
            beforePort = { delay(2_500) },
        )
        val first = async { fixture.controller.restoreFromNotification(fixture.gateway.connectionState) }
        val duplicate = async(duplicateDispatcher) {
            fixture.controller.restoreFromNotification(fixture.gateway.connectionState)
        }
        runCurrent()
        duplicateDispatcher.holdDispatches = true
        advanceUntilIdle()

        // Audio restoration was verified, but notification cleanup failed.
        assertFalse(first.await())
        assertEquals(AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED, fixture.port.value)
        assertEquals(1, fixture.port.restoreWriteCount)
        assertEquals(1, notifications.cancelCount)
        assertFalse(fixture.controller.state.value.isBusy)
        duplicateDispatcher.resumeDispatches()
        advanceUntilIdle()

        assertFalse(duplicate.await())
        assertEquals(0, notifications.restoreFailedCount)
        assertFalse(notifications.restoreFailureVisible)
    }

}
