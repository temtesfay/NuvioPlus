import Cocoa

let nsEventClass: AnyClass? = NSClassFromString("NSEvent")
let sel = NSSelectorFromString("addLocalMonitorForEventsMatchingMask:handler:")
let method = class_getClassMethod(nsEventClass, sel)
print(method != nil ? "Found method" : "Method NOT found")
