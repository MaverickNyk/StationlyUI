package com.stationly.app.platform

import android.os.Build
import com.stationly.core.model.sdui.DeviceInfo
import java.util.UUID

// composeApp's Android target is dev-only — the shipping Android app is
// `android/`, which has its own persistent DeviceIdProvider. A process-stable
// id is enough here.
actual object DeviceIdentity {

    private val id: String by lazy { UUID.randomUUID().toString() }

    actual fun deviceId(): String = id

    actual fun deviceInfo(): DeviceInfo = DeviceInfo(
        platform = "android",
        osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        appVersion = null
    )
}
