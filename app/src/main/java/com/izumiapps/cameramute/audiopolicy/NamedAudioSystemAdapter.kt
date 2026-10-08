package com.izumiapps.cameramute.audiopolicy

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** UserServiceから利用する、FOR_SYSTEMに限定した名前付き端末API。 */
internal interface ForSystemAudioPolicy {
    fun getForSystem(): Int
    fun setForSystem(config: Int)
}

/**
 * 端末自身のhidden `android.media.AudioSystem` を名前と厳密なsignatureで解決する。
 * Binder transaction番号やParcel形式はアプリ側に持ち込まない。
 *
 * 互換性は機種・OSビルドごとに確認する。クラス・定数・signature・JNIのどれかが
 * 一致しなければfail closedで例外にする。
 */
internal class NamedAudioSystemAdapter internal constructor(
    private val audioSystemClass: Class<*>,
) : ForSystemAudioPolicy {

    internal constructor() : this(loadPlatformAudioSystem())

    private val forSystem = resolveIntConstant("FOR_SYSTEM", AudioPolicyConstants.USAGE_FOR_SYSTEM)
    private val forceNone = resolveIntConstant("FORCE_NONE", AudioPolicyConstants.CONFIG_FORCE_NONE)
    private val forceSystemEnforced = resolveIntConstant(
        "FORCE_SYSTEM_ENFORCED",
        AudioPolicyConstants.CONFIG_FORCE_SYSTEM_ENFORCED,
    )
    private val getter = resolveStaticIntMethod("getForceUse", INT)
    private val setter = resolveStaticIntMethod("setForceUse", INT, INT)

    override fun getForSystem(): Int = invokeInt(getter, forSystem)

    override fun setForSystem(config: Int) {
        require(config == forceNone || config == forceSystemEnforced) {
            "Only FORCE_NONE and FORCE_SYSTEM_ENFORCED are allowed"
        }
        val result = invokeInt(setter, forSystem, config)
        check(result == AUDIO_STATUS_OK) {
            "AudioSystem.setForceUse failed with status $result"
        }
    }

    private fun resolveIntConstant(name: String, expected: Int): Int {
        val field = try {
            audioSystemClass.getDeclaredField(name)
        } catch (e: ReflectiveOperationException) {
            throw unsupported("missing int constant $name", e)
        }
        val modifiers = field.modifiers
        if (
            field.type != INT ||
            !Modifier.isPublic(modifiers) ||
            !Modifier.isStatic(modifiers) ||
            !Modifier.isFinal(modifiers)
        ) {
            throw unsupported("constant $name does not have the expected public static final int signature")
        }
        val actual = try {
            field.getInt(null)
        } catch (e: ReflectiveOperationException) {
            throw unsupported("constant $name cannot be read", e)
        }
        if (actual != expected) {
            throw unsupported("constant $name is $actual, expected $expected")
        }
        return actual
    }

    private fun resolveStaticIntMethod(name: String, vararg parameterTypes: Class<*>): Method {
        val method = try {
            audioSystemClass.getDeclaredMethod(name, *parameterTypes)
        } catch (e: ReflectiveOperationException) {
            throw unsupported("missing method $name with the expected parameters", e)
        }
        val modifiers = method.modifiers
        if (
            method.returnType != INT ||
            !Modifier.isPublic(modifiers) ||
            !Modifier.isStatic(modifiers)
        ) {
            throw unsupported("method $name does not have the expected public static int signature")
        }
        return method
    }

    private fun invokeInt(method: Method, vararg arguments: Int): Int = try {
        method.invoke(null, *arguments.toTypedArray()) as Int
    } catch (e: InvocationTargetException) {
        throw IllegalStateException(
            "AudioSystem.${method.name} is unavailable on this device build",
            e.targetException,
        )
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException(
            "AudioSystem.${method.name} could not be invoked",
            e,
        )
    } catch (e: LinkageError) {
        throw IllegalStateException(
            "AudioSystem.${method.name} could not be linked",
            e,
        )
    }

    private fun unsupported(message: String, cause: Throwable? = null): UnsupportedOperationException =
        UnsupportedOperationException("Unsupported AudioSystem API: $message", cause)

    private companion object {
        const val AUDIO_SYSTEM_CLASS = "android.media.AudioSystem"
        const val AUDIO_STATUS_OK = 0
        val INT: Class<Int> = Int::class.javaPrimitiveType!!

        fun loadPlatformAudioSystem(): Class<*> = try {
            Class.forName(AUDIO_SYSTEM_CLASS)
        } catch (e: ClassNotFoundException) {
            throw UnsupportedOperationException("AudioSystem class is unavailable", e)
        } catch (e: LinkageError) {
            throw UnsupportedOperationException("AudioSystem class could not be linked", e)
        }
    }
}
