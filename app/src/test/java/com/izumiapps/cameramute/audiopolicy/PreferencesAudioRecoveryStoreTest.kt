package com.izumiapps.cameramute.audiopolicy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesAudioRecoveryStoreTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_audio_recovery", Context.MODE_PRIVATE)

    @Before fun clear() { check(prefs.edit().clear().commit()) }

    @Test fun `original persists across store instances and clear persists`() {
        PreferencesAudioRecoveryStore(prefs, "device-build-v1") { 1 }.save(11)
        val recreated = PreferencesAudioRecoveryStore(prefs, "device-build-v1") { 1 }
        assertEquals(11, recreated.load())
        recreated.clear()
        assertNull(PreferencesAudioRecoveryStore(prefs, "device-build-v1") { 1 }.load())
    }

    @Test fun `record is isolated by build and transport version`() {
        PreferencesAudioRecoveryStore(prefs, "build-A-v1") { 1 }.save(11)
        assertNull(PreferencesAudioRecoveryStore(prefs, "build-B-v1") { 1 }.load())
        assertNull(PreferencesAudioRecoveryStore(prefs, "build-A-v2") { 1 }.load())
    }

    @Test fun `non eleven original cannot be persisted`() {
        val store = PreferencesAudioRecoveryStore(prefs, "test") { 1 }
        for (value in listOf(0, -1, 7)) {
            assertThrows(IllegalArgumentException::class.java) { store.save(value) }
        }
        assertNull(store.load())
    }

    @Test fun `corrupt data is not silently treated as missing`() {
        check(prefs.edit().putString("test:original", "eleven").putInt("test:boot", 1).commit())
        assertThrows(ClassCastException::class.java) { PreferencesAudioRecoveryStore(prefs, "test") { 1 }.load() }
    }

    @Test fun `reboot retires previous ownership even when build is unchanged`() {
        PreferencesAudioRecoveryStore(prefs, "same-build") { 1 }.save(11)
        assertNull(PreferencesAudioRecoveryStore(prefs, "same-build") { 2 }.load())
        assertNull(PreferencesAudioRecoveryStore(prefs, "same-build") { 1 }.load())
    }

    @Test fun `unavailable boot count prevents recording an original`() {
        val store = PreferencesAudioRecoveryStore(prefs, "test") { -1 }
        assertThrows(IllegalStateException::class.java) { store.save(11) }
        assertNull(store.load())
    }

    @Test fun `missing boot metadata is not trusted as an original`() {
        check(prefs.edit().putInt("test:original", 11).commit())
        assertThrows(IllegalStateException::class.java) {
            PreferencesAudioRecoveryStore(prefs, "test") { 1 }.load()
        }
    }
}
