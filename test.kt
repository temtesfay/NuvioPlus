import platform.Foundation.NSProcessInfo

fun test() {
    val x = NSProcessInfo.processInfo.isMacCatalystApp
    val y = NSProcessInfo.processInfo.isMacCatalystApp()
}
