package com.nuvio.app

import platform.Foundation.NSProcessInfo
import platform.Foundation.isMacCatalystApp
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

internal actual val isIos: Boolean = true
internal actual val isMacCatalyst: Boolean = NSProcessInfo.processInfo.isMacCatalystApp()