package com.izumiapps.cameramute.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RealShizukuGatewayTest {

    private class FakeRuntime : ShizukuRuntime {
        var available = true
        var backendUid = Process.SHELL_UID
        var permission = PackageManager.PERMISSION_GRANTED
        var bindCallCount = 0
        var detachCallCount = 0
        var removeCallCount = 0
        var connection: ServiceConnection? = null
        var onBind: (() -> Unit)? = null
        var onRelease: (() -> Unit)? = null
        var onPermissionRequest: (() -> Unit)? = null
        val permissionRequestCodes = mutableListOf<Int>()
        val connections = mutableListOf<ServiceConnection>()
        val releaseOperations = mutableListOf<String>()
        lateinit var binderReceived: () -> Unit
        lateinit var binderDead: () -> Unit
        lateinit var permissionResult: () -> Unit

        override fun pingBinder(): Boolean = available

        override fun getUid(): Int = backendUid

        override fun checkSelfPermission(): Int = permission

        override fun requestPermission(requestCode: Int) {
            permissionRequestCodes += requestCode
            onPermissionRequest?.invoke()
        }

        override fun setBinderReceivedListener(listener: () -> Unit) {
            binderReceived = listener
        }

        override fun setBinderDeadListener(listener: () -> Unit) {
            binderDead = listener
        }

        override fun setPermissionResultListener(listener: () -> Unit) {
            permissionResult = listener
        }

        override fun bindUserService(connection: ServiceConnection) {
            bindCallCount += 1
            this.connection = connection
            connections += connection
            onBind?.invoke()
        }

        override fun releaseUserService(connection: ServiceConnection) {
            detachCallCount += 1
            releaseOperations += "detach"
            removeCallCount += 1
            releaseOperations += "remove"
            onRelease?.invoke()
        }

        fun connect(
            service: IAudioPolicyUserService,
            target: ServiceConnection = connection!!,
        ) {
            target.onServiceConnected(
                ComponentName("com.izumiapps.cameramute", AudioPolicyUserService::class.java.name),
                service.asBinder(),
            )
        }

        fun disconnect(target: ServiceConnection = connection!!) {
            target.onServiceDisconnected(
                ComponentName("com.izumiapps.cameramute", AudioPolicyUserService::class.java.name),
            )
        }
    }

    private class FakeUserService : IAudioPolicyUserService.Stub() {
        var current = AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED
        var failOnGet = false
        var getCallCount = 0
        var linkToDeathCallCount = 0
        var unlinkToDeathCallCount = 0
        var onLinkToDeath: (() -> Unit)? = null

        override fun destroy() = Unit

        override fun getForSystem(): Int {
            getCallCount += 1
            if (failOnGet) error("named API unavailable")
            return current
        }

        override fun setForSystem(config: Int) {
            current = config
        }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            linkToDeathCallCount += 1
            onLinkToDeath?.invoke()
            super.linkToDeath(recipient, flags)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            unlinkToDeathCallCount += 1
            return super.unlinkToDeath(recipient, flags)
        }
    }

    @Test
    fun `obtain waits for asynchronous bind and validates the named API`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()

        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(1, runtime.bindCallCount)
        runtime.connect(service)
        val port = pending.await()

        assertEquals(
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM),
        )
    }

    @Test
    fun `a valid bound port is reused without starting another service`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()

        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(service)
        val first = pending.await()
        val second = gateway.obtainAudioPolicyPort()

        assertTrue(first === second)
        assertEquals(1, runtime.bindCallCount)
    }

    @Test
    fun `service disconnect invalidates the previously returned port`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(service)
        val port = pending.await()

        runtime.disconnect()

        assertThrows(IllegalStateException::class.java) {
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        }
    }

    @Test
    fun `Shizuku binder death invalidates the previously returned port`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(service)
        val port = pending.await()

        runtime.available = false
        runtime.binderDead()

        assertThrows(IllegalStateException::class.java) {
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        }
        assertEquals(ShizukuConnectionState.NOT_AVAILABLE, gateway.connectionState.value)
    }

    @Test
    fun `live permission revocation invalidates a previously returned port`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(service)
        val port = pending.await()

        runtime.permission = PackageManager.PERMISSION_DENIED

        assertThrows(IllegalStateException::class.java) {
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        }
        assertEquals(ShizukuConnectionState.PERMISSION_DENIED, gateway.connectionState.value)
    }

    @Test
    fun `root Shizuku backend is reported as unsupported and rejected before binding`() = runTest {
        val runtime = FakeRuntime().apply { backendUid = Process.ROOT_UID }
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)

        val result = runCatching { gateway.obtainAudioPolicyPort() }
        gateway.requestPermission(42)

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(ShizukuConnectionState.UNSUPPORTED_BACKEND, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertEquals(0, runtime.bindCallCount)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `unknown non-shell backend is rejected without offering permission`() = runTest {
        val runtime = FakeRuntime().apply { backendUid = 12345 }
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)

        val result = runCatching { gateway.obtainAudioPolicyPort() }
        gateway.requestPermission(42)

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(ShizukuConnectionState.UNSUPPORTED_BACKEND, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertEquals(0, runtime.bindCallCount)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `disconnected binder is unavailable even if the last backend was root`() {
        val runtime = FakeRuntime().apply {
            available = false
            backendUid = Process.ROOT_UID
        }
        val gateway = RealShizukuGateway(runtime)

        gateway.requestPermission(42)

        assertEquals(ShizukuConnectionState.NOT_AVAILABLE, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `shell backend without permission offers and forwards permission request`() {
        val runtime = FakeRuntime().apply { permission = PackageManager.PERMISSION_DENIED }
        val gateway = RealShizukuGateway(runtime)

        assertTrue(gateway.connectionState.value.canRequestPermission)
        gateway.requestPermission(42)

        assertEquals(listOf(42), runtime.permissionRequestCodes)
        assertEquals(ShizukuConnectionState.PERMISSION_DENIED, gateway.connectionState.value)
    }

    @Test
    fun `authorized shell backend does not offer or repeat permission request`() {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime)

        gateway.requestPermission(42)

        assertEquals(ShizukuConnectionState.READY, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `permission request refreshes a backend that changed after the button was shown`() {
        val runtime = FakeRuntime().apply { permission = PackageManager.PERMISSION_DENIED }
        val gateway = RealShizukuGateway(runtime)
        runtime.backendUid = Process.ROOT_UID

        gateway.requestPermission(42)

        assertEquals(ShizukuConnectionState.UNSUPPORTED_BACKEND, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `permission request refreshes a binder that disconnected after the button was shown`() {
        val runtime = FakeRuntime().apply { permission = PackageManager.PERMISSION_DENIED }
        val gateway = RealShizukuGateway(runtime)
        runtime.available = false

        gateway.requestPermission(42)

        assertEquals(ShizukuConnectionState.NOT_AVAILABLE, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
        assertTrue(runtime.permissionRequestCodes.isEmpty())
    }

    @Test
    fun `binder loss during permission request updates state without throwing`() {
        val runtime = FakeRuntime().apply { permission = PackageManager.PERMISSION_DENIED }
        val gateway = RealShizukuGateway(runtime)
        runtime.onPermissionRequest = {
            runtime.available = false
            throw IllegalStateException("Binder disconnected")
        }

        gateway.requestPermission(42)

        assertEquals(listOf(42), runtime.permissionRequestCodes)
        assertEquals(ShizukuConnectionState.NOT_AVAILABLE, gateway.connectionState.value)
        assertFalse(gateway.connectionState.value.canRequestPermission)
    }

    @Test
    fun `restarting root Shizuku with shell backend makes permission request available`() {
        val runtime = FakeRuntime().apply {
            backendUid = Process.ROOT_UID
            permission = PackageManager.PERMISSION_DENIED
        }
        val gateway = RealShizukuGateway(runtime)
        assertEquals(ShizukuConnectionState.UNSUPPORTED_BACKEND, gateway.connectionState.value)

        runtime.backendUid = Process.SHELL_UID
        runtime.binderReceived()

        assertEquals(ShizukuConnectionState.PERMISSION_DENIED, gateway.connectionState.value)
        assertTrue(gateway.connectionState.value.canRequestPermission)
    }

    @Test
    fun `backend changing away from shell invalidates a returned port`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(service)
        val port = pending.await()

        runtime.backendUid = Process.ROOT_UID

        assertThrows(IllegalStateException::class.java) {
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
        }
        assertEquals(ShizukuConnectionState.UNSUPPORTED_BACKEND, gateway.connectionState.value)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
    }

    @Test
    fun `bind timeout is bounded and detaches the pending connection`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 100)
        val service = FakeUserService()

        var failure: Throwable? = null
        try {
            gateway.obtainAudioPolicyPort()
        } catch (e: Exception) {
            failure = e
        }

        assertTrue(failure is TimeoutCancellationException)
        assertEquals(listOf("detach", "remove"), runtime.releaseOperations)

        val timedOutConnection = runtime.connections.single()
        val lateService = FakeUserService()
        val retry = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        val retryConnection = runtime.connection!!
        assertNotSame(timedOutConnection, retryConnection)

        // 旧callbackが再bind後に遅れても、新しいUserServiceをremoveしてはならない。
        runtime.connect(lateService, timedOutConnection)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
        assertEquals(0, lateService.getCallCount)
        assertEquals(0, lateService.linkToDeathCallCount)

        runtime.connect(service, retryConnection)
        val port = retry.await()
        assertEquals(
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM),
        )
        assertEquals(2, service.getCallCount)
        assertEquals(1, service.linkToDeathCallCount)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)

        // timeoutした旧接続の切断通知も、新しい有効なportへ波及しない。
        runtime.disconnect(timedOutConnection)
        assertEquals(
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM),
        )
        assertEquals(3, service.getCallCount)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
    }

    @Test
    fun `cancellation racing with service callback invalidates the candidate lease`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        service.onLinkToDeath = { pending.cancel() }
        runCurrent()

        runtime.connect(service)
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(0, service.getCallCount)
        assertEquals(1, service.linkToDeathCallCount)
        assertEquals(1, service.unlinkToDeathCallCount)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
        assertEquals(listOf("detach", "remove"), runtime.releaseOperations)
    }

    @Test
    fun `cancellation while bind starts releases the new service exactly once`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val lateService = FakeUserService()
        val pending = async { gateway.obtainAudioPolicyPort() }
        runtime.onBind = { pending.cancel() }

        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, runtime.bindCallCount)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
        assertEquals(listOf("detach", "remove"), runtime.releaseOperations)

        runtime.connect(lateService)
        assertEquals(0, lateService.getCallCount)
        assertEquals(0, lateService.linkToDeathCallCount)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
    }

    @Test
    fun `new bind waits until invalidated lease release is enqueued`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 5_000)
        val firstService = FakeUserService()
        val firstPending = async { gateway.obtainAudioPolicyPort() }
        runCurrent()
        runtime.connect(firstService)
        firstPending.await()

        val releaseEntered = CountDownLatch(1)
        val allowReleaseToReturn = CountDownLatch(1)
        val secondBindEntered = CountDownLatch(1)
        val releaseWasEnqueuedFirst = AtomicBoolean(false)
        runtime.onRelease = {
            releaseEntered.countDown()
            check(allowReleaseToReturn.await(2, TimeUnit.SECONDS))
        }
        runtime.onBind = {
            if (runtime.bindCallCount == 2) {
                releaseWasEnqueuedFirst.set(
                    runtime.releaseOperations == listOf("detach", "remove"),
                )
                secondBindEntered.countDown()
            }
        }

        val invalidate = async(Dispatchers.Default) { runtime.disconnect() }
        assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
        val retry = async(Dispatchers.Default) { gateway.obtainAudioPolicyPort() }

        assertFalse(secondBindEntered.await(200, TimeUnit.MILLISECONDS))
        allowReleaseToReturn.countDown()
        invalidate.await()
        assertTrue(secondBindEntered.await(2, TimeUnit.SECONDS))
        assertTrue(releaseWasEnqueuedFirst.get())

        val secondService = FakeUserService()
        runtime.connect(secondService)
        val port = retry.await()
        assertEquals(
            AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
            port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM),
        )
    }

    @Test
    fun `failed named API handshake never publishes a port`() = runTest {
        val runtime = FakeRuntime()
        val gateway = RealShizukuGateway(runtime, bindTimeoutMillis = 1_000)
        val service = FakeUserService().apply { failOnGet = true }
        val pending = async { runCatching { gateway.obtainAudioPolicyPort() } }
        runCurrent()
        runtime.connect(service)

        val result = pending.await()

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, runtime.detachCallCount)
        assertEquals(1, runtime.removeCallCount)
    }
}
