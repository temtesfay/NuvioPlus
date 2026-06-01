import Foundation
import UIKit

class MetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { return super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

    override var wantsExtendedDynamicRangeContent: Bool {
        get { return super.wantsExtendedDynamicRangeContent }
        set {
            #if targetEnvironment(macCatalyst)
            // On Mac Catalyst, EDR is intentionally disabled (MoltenVK + EDR causes
            // drawable acquisition failures on SDR Mac displays).  MoltenVK calls this
            // setter from its render thread when VK_EXT_hdr_metadata is active.  The
            // original DispatchQueue.main.sync caused a deadlock: MoltenVK render thread
            // waiting for main → main thread blocked in CoreAnimation's drawable-lock
            // path → deadlock.  On Mac Catalyst we simply drop background-thread writes
            // (EDR stays pinned to the value set from the main thread in viewDidLoad).
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            }
            // Background-thread writes from MoltenVK are intentionally ignored.
            #else
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.sync {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
            #endif
        }
    }
}
