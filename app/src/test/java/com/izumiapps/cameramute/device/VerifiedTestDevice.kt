package com.izumiapps.cameramute.device

/** Synthetic build identity for control-path tests, never a physical-device verification claim. */
object VerifiedTestDevice {
    val record = SupportedDevice("test-oppo", "test-display", "test-only-fingerprint")
    fun guard() = DeviceGuard(record.model, record.display, listOf(record),
        currentManufacturer = "OPPO", currentBrand = "OPPO", currentFingerprint = record.fingerprint!!)
}
