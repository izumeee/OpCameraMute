package com.izumiapps.cameramute.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGuardTest {

    private val observedFindX9 = SupportedDevice(
        model = "CPH2797",
        display = "CPH2797_16.0.10.501(EX01)",
        fingerprint = "OPPO/CPH2797/OP5E8BL1:16/BP2A.250605.015/B.R4T3_JP.d15960_182c05d_17ca7dc:user/release-keys",
    )

    @Test fun `production registry verifies the physically tested Find X9 build`() {
        val guard = observedGuard(observedFindX9)
        assertTrue(guard.isVerified)
        assertFalse(guard.isUnverified)
    }

    private val observedReno13A = SupportedDevice(
        model = "A501OP",
        display = "A501OP_16.0.5.1001(EX01)",
        fingerprint = "OPPO/A501OP/OP5EC7L1:16/BP2A.250605.015/V.R4T2.1ed97e5_552621_52974b:user/release-keys",
    )

    @Test fun `production registry verifies the physically tested Reno13 A build`() {
        val guard = observedGuard(observedReno13A)
        assertTrue(guard.isVerified)
        assertFalse(guard.isUnverified)
    }

    @Test fun `production registry requires the exact tested model display and fingerprint`() {
        for (changed in listOf(
            observedFindX9.copy(model = "other-oppo-model"),
            observedFindX9.copy(display = "other-os-build"),
            observedFindX9.copy(fingerprint = "other-os-fingerprint"),
            observedReno13A.copy(display = "A501OP_16.0.3.501(EX01)"),
            observedReno13A.copy(fingerprint = "other-os-fingerprint"),
        )) {
            val guard = observedGuard(changed)
            assertFalse(guard.isVerified)
            assertTrue(guard.isUnverified)
        }
    }

    @Test fun `production registry rejects missing and unknown fingerprint on the tested model`() {
        for (fingerprint in listOf("", "unknown")) {
            val guard = observedGuard(observedFindX9.copy(fingerprint = fingerprint))
            assertFalse(guard.isVerified)
            assertTrue(guard.isUnverified)
        }
    }

    private fun observedGuard(device: SupportedDevice) = DeviceGuard(
        currentModel = device.model,
        currentDisplay = device.display,
        currentFingerprint = device.fingerprint.orEmpty(),
        currentManufacturer = "OPPO",
        currentBrand = "OPPO",
    )

    @Test fun `same display with a changed OS fingerprint invalidates saved safety state`() {
        val before = DeviceGuard("other", "same-display", currentManufacturer = "OPPO", currentFingerprint = "build-A")
        val after = DeviceGuard("other", "same-display", currentManufacturer = "OPPO", currentFingerprint = "build-B")
        assertFalse(before.safetyKey == after.safetyKey)
    }

    @Test
    fun `exact model display and fingerprint is verified`() {
        val guard = VerifiedTestDevice.guard()
        assertTrue(guard.isVerified)
    }

    @Test
    fun `same OPPO model after OTA is unverified`() {
        val guard = DeviceGuard(
            currentModel = VerifiedDevices.findX9Jp.model,
            currentDisplay = "CPH2797_16.0.11.000(EX01)",
            currentFingerprint = "test-ota-fingerprint",
            currentManufacturer = "OPPO",
            currentBrand = "OPPO",
        )
        assertFalse(guard.isVerified)
        assertTrue(guard.isUnverified)
    }

    @Test
    fun `different model is unverified`() {
        val guard = DeviceGuard(currentModel = "Pixel_8", currentDisplay = "AP4A.250105.002")
        assertTrue(guard.isUnverified)
        assertFalse(guard.isVerified)
    }

    @Test
    fun `current exposes the raw model and display for display purposes`() {
        val guard = DeviceGuard(currentModel = "Pixel_8", currentDisplay = "AP4A.250105.002")
        assertTrue(guard.current.model == "Pixel_8" && guard.current.display == "AP4A.250105.002")
    }

    @Test
    fun `verified registry with multiple entries matches any of them`() {
        val other = SupportedDevice(model = "CPH2719", display = "CPH2719_15.0.1.000(EX01)", fingerprint = "test-other")
        val guard = DeviceGuard(
            currentModel = other.model,
            currentDisplay = other.display,
            currentFingerprint = other.fingerprint!!,
            verifiedDevices = listOf(VerifiedDevices.findX9Jp, other),
        )
        assertTrue(guard.isVerified)
    }

    @Test
    fun `unlisted device is unverified`() {
        val other = SupportedDevice(model = "CPH2719", display = "CPH2719_15.0.1.000(EX01)", fingerprint = "test-other")
        val guard = DeviceGuard(
            currentModel = "Pixel_8",
            currentDisplay = "AP4A.250105.002",
            verifiedDevices = listOf(VerifiedDevices.findX9Jp, other),
        )
        assertTrue(guard.isUnverified)
        assertFalse(guard.isVerified)
    }

    @Test
    fun `empty verified list never labels a device verified`() {
        val guard = DeviceGuard(
            currentModel = VerifiedDevices.findX9Jp.model,
            currentDisplay = VerifiedDevices.findX9Jp.display,
            verifiedDevices = emptyList(),
        )
        assertFalse(guard.isVerified)
    }

    @Test
    fun `unlisted OPPO model is never called verified`() {
        val guard = DeviceGuard("other", "build", currentManufacturer = "oppo", currentBrand = "OPPO", currentFingerprint = "test-oppo")
        assertTrue(guard.isUnverified)
        assertFalse(guard.isVerified)
    }

    @Test
    fun `vendor names and missing identity do not grant verified status`() {
        for (vendor in listOf("", "Oplus", "OnePlus", "realme", "NotOPPO")) {
            assertTrue(DeviceGuard("other", "build", currentManufacturer = vendor,
                currentBrand = vendor, currentFingerprint = "test-other").isUnverified)
        }
        assertTrue(DeviceGuard("", "build", currentManufacturer = "OPPO").isUnverified)
        assertTrue(DeviceGuard("other", "", currentManufacturer = "OPPO").isUnverified)
        assertTrue(DeviceGuard("other", "build", currentManufacturer = "OPPO", currentFingerprint = "").isUnverified)
        assertTrue(DeviceGuard("other", "build", currentManufacturer = "OPPO", currentFingerprint = "unknown").isUnverified)
    }

    @Test fun `changed fingerprint is unverified even with identical model and display`() {
        val record = VerifiedTestDevice.record
        val guard = DeviceGuard(record.model, record.display, listOf(record),
            currentManufacturer = "OPPO", currentFingerprint = "updated-test-fingerprint")
        assertFalse(guard.isVerified)
        assertTrue(guard.isUnverified)
    }

    @Test fun `historical record without fingerprint never grants verified exception`() {
        val record = observedFindX9.copy(fingerprint = null)
        val guard = DeviceGuard(record.model, record.display, verifiedDevices = listOf(record),
            currentManufacturer = "OPPO", currentFingerprint = observedFindX9.fingerprint!!)
        assertFalse(guard.isVerified)
        assertTrue(guard.isUnverified)
    }
}
