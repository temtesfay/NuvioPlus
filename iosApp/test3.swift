import Cocoa

let nsCursorClass: AnyClass? = NSClassFromString("NSCursor")
let sel = NSSelectorFromString("setHiddenUntilMouseMoves:")
let method = class_getClassMethod(nsCursorClass, sel)
let imp = method_getImplementation(method!)
typealias SetHiddenCFunction = @convention(c) (AnyObject, Selector, Bool) -> Void
let setHidden = unsafeBitCast(imp, to: SetHiddenCFunction.self)
setHidden(nsCursorClass as AnyObject, sel, true)
print("Successfully called setHiddenUntilMouseMoves!")
