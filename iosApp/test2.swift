import Cocoa

let nsCursorClass: AnyClass? = NSClassFromString("NSCursor")
let sel = NSSelectorFromString("setHiddenUntilMouseMoves:")
let method = class_getClassMethod(nsCursorClass, sel)
print(method != nil ? "Found method setHiddenUntilMouseMoves" : "Method NOT found")
