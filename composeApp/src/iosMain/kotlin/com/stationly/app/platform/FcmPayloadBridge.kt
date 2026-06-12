package com.stationly.app

import com.stationly.core.platform.FcmPayloadBridge as CoreFcmPayloadBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Exported from composeApp so Swift can access it via composeApp.xcframework.
// Kotlin/Native generates the completionHandler/async bridge for suspend
// functions only in the ROOT framework module — core's own suspend funcs are
// absent from the ObjC header, hence these thin delegates.
object FcmPayloadBridge {
    fun processPayload(jsonString: String) = CoreFcmPayloadBridge.processPayload(jsonString)

    /**
     * Swift sees `processPayloadAndWait(jsonString:completionHandler:)` (async).
     * Returns only after SQLite + the App Group writes have landed, so
     * AppDelegate can reload the widget and call the background-fetch
     * completion handler without racing iOS process suspension.
     * K/N requires the CALL to start on the main thread; the immediate
     * withContext hop keeps parsing/persisting off it.
     */
    suspend fun processPayloadAndWait(jsonString: String) = withContext(Dispatchers.Default) {
        CoreFcmPayloadBridge.processPayloadAndWait(jsonString)
    }
}
