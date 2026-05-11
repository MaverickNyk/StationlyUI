package com.stationly.app

import com.stationly.core.platform.FcmPayloadBridge as CoreFcmPayloadBridge

// Exported from composeApp so Swift can access it via composeApp.xcframework
object FcmPayloadBridge {
    fun processPayload(jsonString: String) = CoreFcmPayloadBridge.processPayload(jsonString)
}
