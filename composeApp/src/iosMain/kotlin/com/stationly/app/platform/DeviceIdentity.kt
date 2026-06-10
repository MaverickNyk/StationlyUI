package com.stationly.app.platform

import com.stationly.core.model.sdui.DeviceInfo
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice

actual object DeviceIdentity {

    // Stored in the APP-GROUP defaults, not the standard domain: logout runs
    // IosStorageManager.clearAll() → removePersistentDomainForName(bundleId),
    // which would wipe a standard-domain id and orphan the backend session
    // (same reason Android keeps its id in a separate prefs file).
    private val suite = NSUserDefaults(suiteName = "group.com.stationly.mobile")

    actual fun deviceId(): String {
        suite.stringForKey("stationly_device_id")?.let { if (it.isNotBlank()) return it }
        val id = NSUUID().UUIDString
        suite.setObject(id, forKey = "stationly_device_id")
        suite.synchronize()
        return id
    }

    actual fun deviceInfo(): DeviceInfo {
        val device = UIDevice.currentDevice
        val appVersion = NSBundle.mainBundle
            .objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        return DeviceInfo(
            platform = "ios",
            osVersion = "${device.systemName} ${device.systemVersion}",
            model = device.model,
            appVersion = appVersion
        )
    }
}
