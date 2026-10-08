package com.izumiapps.cameramute.audiopolicy

/** Tests only: production must use a durable store before changing system audio. */
class MemoryAudioRecoveryStore(initial: Int? = null) : AudioRecoveryStore {
    var original: Int? = initial
    var failLoad = false
    var failSave = false
    var failClear = false

    override fun load(): Int? {
        check(!failLoad)
        return original
    }
    override fun save(original: Int) {
        check(!failSave)
        this.original = original
    }
    override fun clear() {
        check(!failClear)
        original = null
    }
}
