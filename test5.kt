import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomMac

fun test() {
    val b = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomMac
}
