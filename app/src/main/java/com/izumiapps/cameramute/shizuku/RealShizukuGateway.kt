package com.izumiapps.cameramute.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.izumiapps.cameramute.BuildConfig
import com.izumiapps.cameramute.audiopolicy.AudioPolicyBinderClient
import com.izumiapps.cameramute.audiopolicy.AudioPolicyConstants
import com.izumiapps.cameramute.audiopolicy.AudioPolicyPort
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Shizukuのstatic APIを単体テストから切り離す最小境界。 */
internal interface ShizukuRuntime {
    fun pingBinder(): Boolean
    fun getUid(): Int
    fun checkSelfPermission(): Int
    fun requestPermission(requestCode: Int)
    fun setBinderReceivedListener(listener: () -> Unit)
    fun setBinderDeadListener(listener: () -> Unit)
    fun setPermissionResultListener(listener: () -> Unit)
    fun bindUserService(connection: ServiceConnection)
    fun releaseUserService(connection: ServiceConnection)
}

private class AndroidShizukuRuntime(context: Context) : ShizukuRuntime {
    private val serviceComponent =
        ComponentName(context.packageName, AudioPolicyUserService::class.java.name)
    private val userServiceArgs = Shizuku.UserServiceArgs(
        serviceComponent,
    )
        .daemon(false)
        .tag(USER_SERVICE_TAG)
        .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun pingBinder(): Boolean = Shizuku.pingBinder()

    override fun getUid(): Int = Shizuku.getUid()

    override fun checkSelfPermission(): Int = Shizuku.checkSelfPermission()

    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

    override fun setBinderReceivedListener(listener: () -> Unit) {
        Shizuku.addBinderReceivedListenerSticky(Shizuku.OnBinderReceivedListener { listener() })
    }

    override fun setBinderDeadListener(listener: () -> Unit) {
        Shizuku.addBinderDeadListener(Shizuku.OnBinderDeadListener { listener() })
    }

    override fun setPermissionResultListener(listener: () -> Unit) {
        Shizuku.addRequestPermissionResultListener(
            Shizuku.OnRequestPermissionResultListener { _, _ -> listener() },
        )
    }

    override fun bindUserService(connection: ServiceConnection) {
        check(mainHandler.post {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not bind AudioSystem UserService", e)
                try {
                    connection.onNullBinding(serviceComponent)
                } catch (callbackError: Exception) {
                    Log.w(LOG_TAG, "Could not report UserService bind failure", callbackError)
                }
            }
        }) { "Could not enqueue AudioSystem UserService bind" }
    }

    override fun releaseUserService(connection: ServiceConnection) {
        check(mainHandler.post {
            // remove=falseだけがShizuku API内の接続キャッシュとcallbackを解除する。
            try {
                Shizuku.unbindUserService(userServiceArgs, connection, false)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not detach AudioSystem UserService callback", e)
            }
            // 必ずdetachの後で、予約済みdestroy()を呼んでshell UIDプロセスを終了する。
            try {
                Shizuku.unbindUserService(userServiceArgs, null, true)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not remove AudioSystem UserService", e)
            }
        }) { "Could not enqueue AudioSystem UserService release" }
    }

    private companion object {
        const val LOG_TAG = "CameraMuteShizuku"
        const val USER_SERVICE_TAG = "audio-policy-named-v1"
        const val USER_SERVICE_PROCESS_SUFFIX = "audio_policy"
    }
}

/**
 * Shizuku UserServiceへ非同期bindし、端末自身のnamed AudioSystem APIだけを公開する。
 *
 * UserService経路の互換性は機種・OSビルドごとに確認する。bind、名前付きAPIの読み取り、
 * 権限、生存確認のいずれかが失敗した場合はportを公開せず、raw Binderへフォールバックしない。
 */
class RealShizukuGateway internal constructor(
    private val runtime: ShizukuRuntime,
    private val bindTimeoutMillis: Long = DEFAULT_BIND_TIMEOUT_MILLIS,
) : ShizukuGateway {

    constructor(context: Context) : this(AndroidShizukuRuntime(context.applicationContext))

    private val generation = AtomicLong(0)
    private val lifecycleLock = Any()
    private var activeLease: BoundPortLease? = null
    private val bindMutex = Mutex()
    private val _connectionState = MutableStateFlow(computeState())
    override val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    init {
        runtime.setBinderReceivedListener(::handleRuntimeChanged)
        runtime.setBinderDeadListener(::handleRuntimeChanged)
        runtime.setPermissionResultListener(::handleRuntimeChanged)
    }

    override fun requestPermission(requestCode: Int) {
        try {
            if (!updateStateFromRuntime().canRequestPermission) return
            runtime.requestPermission(requestCode)
        } catch (_: Exception) {
            handleRuntimeChanged()
        }
    }

    override suspend fun obtainAudioPolicyPort(): AudioPolicyPort = bindMutex.withLock {
        updateStateFromRuntime()
        check(_connectionState.value == ShizukuConnectionState.READY) {
            "Shizuku is not ready (state=${_connectionState.value})"
        }

        synchronized(lifecycleLock) {
            activeLease?.let { existing ->
                if (existing.isUsableLocked()) return@withLock existing.port
            }
        }

        val expectedGeneration = generation.get()
        var candidate: BoundPortLease? = null
        try {
            val lease = withTimeout(bindTimeoutMillis) {
                awaitBoundLease(expectedGeneration).also { candidate = it }
            }
            // 読み取りだけのhandshakeで、端末のクラス・定数・signature・JNIを検証する。
            lease.port.getForceUse(AudioPolicyConstants.USAGE_FOR_SYSTEM)
            check(lease.publishAsActive()) {
                "Shizuku connection changed during AudioSystem validation"
            }
            lease.port
        } catch (e: Exception) {
            candidate?.invalidate()
            throw e
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun awaitBoundLease(expectedGeneration: Long): BoundPortLease =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val candidate = AtomicReference<BoundPortLease?>(null)
            val bindState = AtomicInteger(BIND_NEW)

            lateinit var connection: ServiceConnection

            fun performRelease() {
                synchronized(lifecycleLock) {
                    runtime.releaseUserService(connection)
                }
            }

            fun releaseAttempt() {
                while (true) {
                    when (val state = bindState.get()) {
                        BIND_NEW -> {
                            if (bindState.compareAndSet(state, BIND_CANCELLED_BEFORE_START)) return
                        }
                        BIND_IN_PROGRESS -> {
                            // bindUserServiceの完了前にはremoveしない。完了側が必ず後始末する。
                            if (bindState.compareAndSet(state, BIND_RELEASE_PENDING)) return
                        }
                        BIND_ACTIVE -> {
                            if (bindState.compareAndSet(state, BIND_RELEASED)) {
                                performRelease()
                                return
                            }
                        }
                        BIND_CANCELLED_BEFORE_START,
                        BIND_RELEASE_PENDING,
                        BIND_RELEASED,
                        -> return
                    }
                }
            }

            fun finishBinding() {
                while (true) {
                    when (val state = bindState.get()) {
                        BIND_IN_PROGRESS -> {
                            if (bindState.compareAndSet(state, BIND_ACTIVE)) return
                        }
                        BIND_RELEASE_PENDING -> {
                            if (bindState.compareAndSet(state, BIND_RELEASED)) {
                                performRelease()
                                return
                            }
                        }
                        else -> return
                    }
                }
            }

            fun fail(message: String, cause: Throwable? = null): Boolean {
                if (!completed.compareAndSet(false, true)) return false
                try {
                    releaseAttempt()
                } catch (_: Exception) {
                    // Shizuku Binder死亡後などは解除不能なため、元の失敗を優先する。
                }
                continuation.tryResumeWithException(IllegalStateException(message, cause))?.let {
                    continuation.completeResume(it)
                }
                return true
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (completed.get()) {
                        try {
                            releaseAttempt()
                        } catch (_: Exception) {
                            // 既に失敗した試行なので、遅延callbackから例外を伝播させない。
                        }
                        return
                    }
                    if (
                        expectedGeneration != generation.get() ||
                        computeState() != ShizukuConnectionState.READY
                    ) {
                        fail("Shizuku state changed while binding AudioSystem UserService")
                        return
                    }
                    if (!binder.isBinderAlive || !binder.pingBinder()) {
                        fail("AudioSystem UserService returned a dead Binder")
                        return
                    }

                    val service = IAudioPolicyUserService.Stub.asInterface(binder)
                    if (service == null) {
                        fail("AudioSystem UserService interface was unavailable")
                        return
                    }

                    val lease = BoundPortLease(binder, expectedGeneration, ::releaseAttempt)
                    try {
                        binder.linkToDeath(lease.deathRecipient, 0)
                    } catch (e: Exception) {
                        lease.invalidate()
                        fail("AudioSystem UserService died while binding", e)
                        return
                    }
                    // link完了後に公開する。途中でキャンセルされた場合も、直後のCAS失敗側で
                    // 登録済みDeathRecipientを確実に解除できる。
                    candidate.set(lease)
                    if (!completed.compareAndSet(false, true)) {
                        lease.invalidate()
                        return
                    }
                    val resumeToken = continuation.tryResume(lease)
                    if (resumeToken == null) {
                        lease.invalidate()
                        return
                    }
                    continuation.completeResume(resumeToken)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (!fail("AudioSystem UserService disconnected while binding")) {
                        // 完了済みの別試行へ波及させず、この接続のleaseだけを無効化する。
                        candidate.get()?.invalidate()
                    }
                }

                override fun onBindingDied(name: ComponentName) {
                    onServiceDisconnected(name)
                }

                override fun onNullBinding(name: ComponentName) {
                    fail("AudioSystem UserService returned a null binding")
                }
            }

            continuation.invokeOnCancellation {
                completed.set(true)
                try {
                    candidate.get()?.invalidate() ?: releaseAttempt()
                } catch (_: Exception) {
                    // キャンセル原因を、解除処理の失敗で置き換えない。
                }
            }

            if (!bindState.compareAndSet(BIND_NEW, BIND_IN_PROGRESS)) {
                return@suspendCancellableCoroutine
            }
            try {
                synchronized(lifecycleLock) {
                    runtime.bindUserService(connection)
                }
            } catch (e: Exception) {
                try {
                    finishBinding()
                } catch (_: Exception) {
                    // bind失敗を優先する。
                }
                fail("Could not bind AudioSystem UserService", e)
                return@suspendCancellableCoroutine
            }
            try {
                finishBinding()
            } catch (e: Exception) {
                fail("Could not release cancelled AudioSystem UserService", e)
            }
        }

    private fun computeState(): ShizukuConnectionState {
        return try {
            if (!runtime.pingBinder()) return ShizukuConnectionState.NOT_AVAILABLE
            if (runtime.getUid() != Process.SHELL_UID) {
                return ShizukuConnectionState.UNSUPPORTED_BACKEND
            }
            if (runtime.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuConnectionState.READY
            } else {
                ShizukuConnectionState.PERMISSION_DENIED
            }
        } catch (_: Exception) {
            ShizukuConnectionState.NOT_AVAILABLE
        }
    }

    private fun updateStateFromRuntime(): ShizukuConnectionState {
        val current = computeState()
        _connectionState.value = current
        if (current != ShizukuConnectionState.READY) invalidateActiveLease()
        return current
    }

    private fun handleRuntimeChanged() {
        generation.incrementAndGet()
        invalidateActiveLease()
        _connectionState.value = computeState()
    }

    private fun invalidateActiveLease() {
        synchronized(lifecycleLock) {
            activeLease?.invalidateLocked()
        }
    }

    private inner class BoundPortLease(
        private val binder: IBinder,
        private val boundGeneration: Long,
        private val releaseAttempt: () -> Unit,
    ) {
        private var valid = true

        val deathRecipient = IBinder.DeathRecipient { invalidate() }

        val port: AudioPolicyPort = AudioPolicyBinderClient(
            IAudioPolicyUserService.Stub.asInterface(binder),
            isValid = ::isUsable,
            onRemoteFailure = ::invalidate,
        )

        fun isUsable(): Boolean = synchronized(lifecycleLock) { isUsableLocked() }

        fun isUsableLocked(): Boolean {
            if (!valid) return false
            val binderUsable = try {
                binder.isBinderAlive && binder.pingBinder()
            } catch (_: Exception) {
                false
            }
            if (boundGeneration != generation.get() || !binderUsable) {
                invalidateLocked()
                return false
            }
            val currentState = computeState()
            if (currentState != ShizukuConnectionState.READY) {
                _connectionState.value = currentState
                invalidateLocked()
                return false
            }
            return true
        }

        fun publishAsActive(): Boolean = synchronized(lifecycleLock) {
            if (!isUsableLocked()) return@synchronized false
            if (activeLease != null && activeLease !== this) return@synchronized false
            activeLease = this
            true
        }

        fun invalidate() = synchronized(lifecycleLock) { invalidateLocked() }

        fun invalidateLocked() {
            if (!valid) return
            valid = false
            if (activeLease === this) activeLease = null
            try {
                binder.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
                // 既に死亡している場合は解除不要。
            }
            try {
                releaseAttempt()
            } catch (_: Exception) {
                // Shizuku自体の死亡後は解除できないため、ローカル無効化を優先する。
            }
        }
    }

    private companion object {
        const val DEFAULT_BIND_TIMEOUT_MILLIS = 5_000L
        const val BIND_NEW = 0
        const val BIND_IN_PROGRESS = 1
        const val BIND_ACTIVE = 2
        const val BIND_CANCELLED_BEFORE_START = 3
        const val BIND_RELEASE_PENDING = 4
        const val BIND_RELEASED = 5
    }
}
