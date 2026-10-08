package com.izumiapps.cameramute.assist

interface AssistNotifications {
    fun canShowStatus(): Boolean
    fun showManualActive(): Boolean
    fun cancelAll()
    fun showRestoreFailed()
}
