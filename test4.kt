import platform.Foundation.NSProcessInfo

fun test() {
    val a = NSProcessInfo.processInfo.isiOSAppOnMac
    val b = NSProcessInfo.processInfo.isiOSAppOnMac()
}
