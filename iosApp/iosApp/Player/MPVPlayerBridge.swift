import Foundation
import UIKit
import Libmpv
import ComposeApp
import MediaAccessibility
import CoreText
import ObjectiveC

// MARK: - Player Bridge Implementation (Kotlin protocol conformance)

final class MPVPlayerBridgeImpl: NSObject, NuvioPlayerBridge {

    private var playerVC: MPVPlayerViewController?

    func createPlayerViewController() -> UIViewController {
        let vc = MPVPlayerViewController()
        self.playerVC = vc
        return vc
    }

    func loadFile(url: String) { playerVC?.loadFile(url) }
    func loadFileWithAudio(videoUrl: String, audioUrl: String?, headersJson: String?) {
        playerVC?.loadFile(
            videoUrl,
            audioUrl: audioUrl,
            requestHeaders: parseRequestHeaders(headersJson)
        )
    }
    func play() { playerVC?.playPlayback() }
    func pause() { playerVC?.pausePlayback() }
    func seekTo(positionMs: Int64) { playerVC?.seekToMs(positionMs) }
    func seekBy(offsetMs: Int64) { playerVC?.seekByMs(offsetMs) }
    func retry() { playerVC?.retryPlayback() }
    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int32,
        contrast: Int32,
        saturation: Int32,
        gamma: Int32
    ) {
        playerVC?.configureVideoOutput(
            hardwareDecoder: hardwareDecoder,
            targetColorspaceHint: targetColorspaceHint,
            toneMapping: toneMapping,
            hdrComputePeak: hdrComputePeak,
            targetPrimaries: targetPrimaries,
            targetTransfer: targetTransfer,
            extendedDynamicRange: extendedDynamicRange,
            deband: deband,
            interpolation: interpolation,
            brightness: Int(brightness),
            contrast: Int(contrast),
            saturation: Int(saturation),
            gamma: Int(gamma)
        )
    }
    func setPlaybackSpeed(speed: Float) { playerVC?.setSpeed(speed) }
    func setResizeMode(mode: Int32) { playerVC?.setResize(Int(mode)) }

    // Audio tracks
    func getAudioTrackCount() -> Int32 { Int32(playerVC?.audioTracks.count ?? 0) }
    func getAudioTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getAudioTrackId(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getAudioTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getAudioTrackLang(at: Int32) -> String {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isAudioTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.audioTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    // Subtitle tracks
    func getSubtitleTrackCount() -> Int32 { Int32(playerVC?.subtitleTracks.count ?? 0) }
    func getSubtitleTrackIndex(at: Int32) -> Int32 {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return 0 }
        return Int32(t[Int(at)].index)
    }
    func getSubtitleTrackId(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "0" }
        return "\(t[Int(at)].id)"
    }
    func getSubtitleTrackLabel(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].title
    }
    func getSubtitleTrackLang(at: Int32) -> String {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return "" }
        return t[Int(at)].lang
    }
    func isSubtitleTrackSelected(at: Int32) -> Bool {
        guard let t = playerVC?.subtitleTracks, Int(at) < t.count else { return false }
        return t[Int(at)].selected
    }

    func selectAudioTrack(trackId: Int32) { playerVC?.selectAudio(Int(trackId)) }
    func selectSubtitleTrack(trackId: Int32) { playerVC?.selectSubtitle(Int(trackId)) }
    func setSubtitleUrl(url: String) { playerVC?.addSubtitleUrl(url) }
    func clearExternalSubtitle() { playerVC?.removeExternalSubtitles() }
    func clearExternalSubtitleAndSelect(trackId: Int32) { playerVC?.removeExternalSubtitlesAndSelect(Int(trackId)) }
    func setSubtitleDelayMs(delayMs: Int32) { playerVC?.setSubtitleDelayMs(Int(delayMs)) }
    func applySubtitleStyle(textColor: String, backgroundColor: String, outlineColor: String, outlineSize: Float, bold: Bool, fontSize: Float, subPos: Int32, backColor: String, fontName: String, isBold: Bool) {
        playerVC?.applySubtitleStyle(
            textColor: textColor,
            backgroundColor: backgroundColor,
            outlineColor: outlineColor,
            outlineSize: outlineSize,
            bold: bold,
            fontSize: fontSize,
            subPos: Int(subPos),
            backColor: backColor,
            fontName: fontName,
            isBold: isBold
        )
    }

    /// Reads the user's iOS system caption preferences from MediaAccessibility
    /// (Settings > Accessibility > Subtitles & Captioning) and returns them as a
    /// JSON blob the Kotlin layer can parse. Returns nil if reading fails.
    func readSystemSubtitleStyleJson() -> String? {
        let domain = MACaptionAppearanceDomain.user
        var fgBehavior = MACaptionAppearanceBehavior.useValue
        var fgOpacityBehavior = MACaptionAppearanceBehavior.useValue
        var bgBehavior = MACaptionAppearanceBehavior.useValue
        var bgOpacityBehavior = MACaptionAppearanceBehavior.useValue
        var winBehavior = MACaptionAppearanceBehavior.useValue
        var winOpacityBehavior = MACaptionAppearanceBehavior.useValue
        var fontBehavior = MACaptionAppearanceBehavior.useValue
        var scaleBehavior = MACaptionAppearanceBehavior.useValue

        // Foreground (text) color + opacity
        let fgCG = MACaptionAppearanceCopyForegroundColor(domain, &fgBehavior).takeRetainedValue()
        let fgOpacity = MACaptionAppearanceGetForegroundOpacity(domain, &fgOpacityBehavior)
        let textHex = mpvHexAARRGGBB(cgColor: fgCG, opacity: CGFloat(fgOpacity), label: "foreground")

        // Background color + opacity (the highlight directly behind text characters)
        let bgCG = MACaptionAppearanceCopyBackgroundColor(domain, &bgBehavior).takeRetainedValue()
        let bgOpacity = MACaptionAppearanceGetBackgroundOpacity(domain, &bgOpacityBehavior)

        // Window color + opacity (the larger container box around all captions)
        let winCG = MACaptionAppearanceCopyWindowColor(domain, &winBehavior).takeRetainedValue()
        let winOpacity = MACaptionAppearanceGetWindowOpacity(domain, &winOpacityBehavior)

        // Pick whichever the user actually set. iOS has both "Background" (per-line
        // highlight) and "Window" (full container) in Accessibility settings. Prefer
        // Background if it has opacity, fall back to Window otherwise.
        let bgOpacityCG = CGFloat(bgOpacity)
        let winOpacityCG = CGFloat(winOpacity)
        let backHex: String
        if bgOpacityCG > 0 {
            backHex = mpvHexAARRGGBB(cgColor: bgCG, opacity: bgOpacityCG, label: "background")
        } else if winOpacityCG > 0 {
            backHex = mpvHexAARRGGBB(cgColor: winCG, opacity: winOpacityCG, label: "window")
        } else {
            backHex = mpvHexAARRGGBB(cgColor: bgCG, opacity: bgOpacityCG, label: "background")
        }

        print("[Nuvio] system caption: fgBehavior=\(fgBehavior.rawValue) fgOpacity=\(fgOpacity) bgBehavior=\(bgBehavior.rawValue) bgOpacity=\(bgOpacity) winBehavior=\(winBehavior.rawValue) winOpacity=\(winOpacity)")

        // Font (CTFontDescriptor)
        let fontDesc = MACaptionAppearanceCopyFontDescriptorForStyle(domain, &fontBehavior, .default).takeRetainedValue()
        let rawFamily = CTFontDescriptorCopyAttribute(fontDesc, kCTFontFamilyNameAttribute) as? String
        let rawPostscript = CTFontDescriptorCopyAttribute(fontDesc, kCTFontNameAttribute) as? String
        let fontName = sanitizeSystemFontName(family: rawFamily, postscript: rawPostscript)
        print("[Nuvio] system font raw family=\(rawFamily ?? "nil") postscript=\(rawPostscript ?? "nil") sanitized=\(fontName ?? "nil")")

        // Relative character size — 1.0 = default
        let scale = MACaptionAppearanceGetRelativeCharacterSize(domain, &scaleBehavior)

        // Bold detection via font traits (best-effort)
        var isBold = false
        if let traits = CTFontDescriptorCopyAttribute(fontDesc, kCTFontTraitsAttribute) as? [String: Any],
           let symbolic = traits[kCTFontSymbolicTrait as String] as? UInt32 {
            isBold = (symbolic & CTFontSymbolicTraits.boldTrait.rawValue) != 0
        }

        let dict: [String: Any] = [
            "textColorHex": textHex,
            "backColorHex": backHex,
            "fontName": fontName ?? NSNull(),
            "fontSizeScale": String(format: "%.3f", scale),
            "isBold": isBold ? "true" : "false",
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []),
              let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        print("[Nuvio] readSystemSubtitleStyleJson => \(json)")
        return json
    }

    /// iOS Accessibility may return hidden system fonts (postscript names that
    /// start with ".", like ".AppleSystemUIFontMonospaced") that MPV/CoreText
    /// can't resolve from app code. Map those to public-equivalent PostScript
    /// names that we know work, and pass through any normal font unchanged.
    private func sanitizeSystemFontName(family: String?, postscript: String?) -> String? {
        let hidden = (family?.hasPrefix(".") ?? false) || (postscript?.hasPrefix(".") ?? false)
        if !hidden {
            // Prefer PostScript name (precise weight/style); fall back to family.
            return postscript ?? family
        }
        let combined = ((family ?? "") + " " + (postscript ?? "")).lowercased()
        if combined.contains("mono") {
            return "Menlo-Regular"
        }
        if combined.contains("rounded") {
            return "HelveticaNeue"
        }
        if combined.contains("serif") && !combined.contains("sans") {
            return "Georgia"
        }
        // Default: iOS system UI font surrogate
        return "HelveticaNeue"
    }

    /// Converts a CGColor + opacity into MPV's #AARRGGBB hex string.
    /// Robust against any color space (RGB, grayscale, P3, etc.) by going through UIColor.
    private func mpvHexAARRGGBB(cgColor: CGColor, opacity: CGFloat, label: String) -> String {
        let uiColor = UIColor(cgColor: cgColor)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, aFromColor: CGFloat = 1

        // Try RGB extraction first
        if !uiColor.getRed(&r, green: &g, blue: &b, alpha: &aFromColor) {
            // Fall back to grayscale extraction (e.g., when user picks pure black/white)
            var w: CGFloat = 0
            if uiColor.getWhite(&w, alpha: &aFromColor) {
                r = w; g = w; b = w
            } else {
                print("[Nuvio] WARN: could not extract \(label) color components, defaulting to black")
                r = 0; g = 0; b = 0; aFromColor = 1
            }
        }

        let alpha = max(0, min(1, opacity * aFromColor))
        print("[Nuvio] \(label) color rgba=(\(r),\(g),\(b),\(aFromColor)) opacity=\(opacity) final-alpha=\(alpha)")

        func byte(_ v: CGFloat) -> String {
            let i = max(0, min(255, Int((v * 255).rounded())))
            return String(format: "%02X", i)
        }
        return "#\(byte(alpha))\(byte(r))\(byte(g))\(byte(b))"
    }

    // State - refreshes position from mpv on each call (polled from Kotlin every 250ms)
    func getIsLoading() -> Bool { playerVC?.refreshPlaybackState(); return playerVC?.isPlayerLoading ?? true }
    func getIsPlaying() -> Bool { return playerVC?.isPlayerPlaying ?? false }
    func getIsEnded() -> Bool { return playerVC?.isPlayerEnded ?? false }
    func getDurationMs() -> Int64 { return playerVC?.durationMs ?? 0 }
    func getPositionMs() -> Int64 { return playerVC?.positionMs ?? 0 }
    func getBufferedMs() -> Int64 { return playerVC?.bufferedMs ?? 0 }
    func getPlaybackSpeed() -> Float { playerVC?.currentSpeed ?? 1.0 }
    func getErrorMessage() -> String { playerVC?.currentErrorMessage ?? "" }

    func destroy() {
        playerVC?.destroyPlayer()
        playerVC = nil
    }

    private func parseRequestHeaders(_ headersJson: String?) -> [String: String] {
        guard
            let headersJson,
            !headersJson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            let data = headersJson.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return [:]
        }

        var headers: [String: String] = [:]
        headers.reserveCapacity(raw.count)
        raw.forEach { key, value in
            guard let headerValue = value as? String else { return }
            headers[key] = headerValue
        }
        return headers
    }
}

// MARK: - Track Info

struct TrackInfo {
    let index: Int
    let id: Int
    let type: String
    let title: String
    let lang: String
    let selected: Bool
}

private struct PendingLoadRequest {
    let urlString: String
    let audioUrl: String?
    let requestHeaders: [String: String]
    let queuedAtUptime: TimeInterval
}

// MARK: - MPV Player View Controller

final class MPVPlayerViewController: UIViewController {

    private let errorStateLock = NSLock()
    private var metalLayer = MetalLayer()
    private var lastAppliedDrawableSize: CGSize = .zero
    private var pendingLoadRequest: PendingLoadRequest?
    private var pendingLoadRetryWorkItem: DispatchWorkItem?
    private var mpv: OpaquePointer?
    private lazy var eventQueue = DispatchQueue(label: "mpv-events", qos: .userInitiated)
    private var recentPlaybackLogs: [String] = []
    private var activeRequestHeaders: [String: String] = [:]

    // Cached track lists
    var audioTracks: [TrackInfo] = []
    var subtitleTracks: [TrackInfo] = []

    // MARK: - UIKit Subtitle Overlay
    // Used when the user picks a custom font or a non-zero background opacity.
    // MPV's built-in renderer cannot load system fonts or render rounded corners,
    // so we hide its subtitles (sub-visibility=no) and display them via UIKit.

    private var overlayContainer: UIView?
    private var overlayBackground: UIView?
    private var overlayLabel: UILabel?
    private var overlayBottomConstraint: NSLayoutConstraint?
    private var isUsingSubtitleOverlay = false

    #if targetEnvironment(macCatalyst)
    // Cursor state (Mac Catalyst only).
    // mouseMovedMonitor: opaque token from NSEvent.addLocalMonitorForEventsMatchingMask:handler:
    //   Must be passed to NSEvent.removeMonitor: on teardown.
    // NSEvent local monitors fire at the AppKit event-loop level, unconditionally,
    //   regardless of cursor visibility — unlike UIHoverGestureRecognizer which
    //   stops delivering events once the cursor is hidden.
    //
    // The cursor and player controls are intentionally kept in sync: the cursor
    // only hides when Kotlin decides to hide the controls (via NuvioPlayerHideCursor),
    // so both disappear and reappear together with no independent timers.
    private var mouseMovedMonitor: AnyObject?
    private var hideCursorObserver: NSObjectProtocol?
    #endif

    // State (polled from Kotlin every 250ms)
    var isPlayerLoading: Bool = true
    var isPlayerPlaying: Bool = false
    var isPlayerEnded: Bool = false
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var bufferedMs: Int64 = 0
    var currentSpeed: Float = 1.0
    var currentErrorMessage: String {
        errorStateLock.lock()
        defer { errorStateLock.unlock() }
        return _currentErrorMessage ?? ""
    }
    private var _currentErrorMessage: String?

    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        [.bottom, .left, .right]
    }

    override var prefersStatusBarHidden: Bool {
        true
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.layer.masksToBounds = true

        metalLayer.contentsGravity = .resize
        metalLayer.contentsScale = view.window?.screen.nativeScale ?? 2.0
        #if targetEnvironment(macCatalyst)
        // MoltenVK on macOS needs read access to the framebuffer for certain operations.
        // EDR (extended dynamic range) combined with MoltenVK can cause drawable
        // acquisition failures on non-HDR Mac displays — disable it here and let
        // configureVideoOutput re-enable it if the user's settings request it.
        metalLayer.framebufferOnly = false
        metalLayer.wantsExtendedDynamicRangeContent = false
        #else
        metalLayer.framebufferOnly = true
        metalLayer.wantsExtendedDynamicRangeContent = true
        #endif
        metalLayer.backgroundColor = UIColor.black.cgColor
        view.layer.addSublayer(metalLayer)
        layoutMetalLayer()

        setupMpv()
        setupNotifications()
        setupSubtitleOverlay()
        #if targetEnvironment(macCatalyst)
        setupCursorTracking()
        #endif
        refreshImmersiveSystemUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshImmersiveSystemUI()
        #if targetEnvironment(macCatalyst)
        // Ensure cursor is visible when the player (re-)appears.
        showCursor()
        #endif
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        #if targetEnvironment(macCatalyst)
        // Restore the cursor when navigating away from the player.
        teardownCursorTracking()
        #endif
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutMetalLayer()
        attemptStartPendingLoad()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshImmersiveSystemUI()
        attemptStartPendingLoad()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        layoutMetalLayer()
        refreshImmersiveSystemUI()
        attemptStartPendingLoad()
    }

    private func layoutMetalLayer() {
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return }

        let scale = view.window?.screen.nativeScale ?? 2.0
        let drawableSize = CGSize(
            width: (bounds.width * scale).rounded(.toNearestOrAwayFromZero),
            height: (bounds.height * scale).rounded(.toNearestOrAwayFromZero)
        )

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.contentsScale = scale
        metalLayer.frame = CGRect(origin: .zero, size: bounds.size)
        if drawableSize != lastAppliedDrawableSize {
            metalLayer.drawableSize = drawableSize
            lastAppliedDrawableSize = drawableSize
        }
        CATransaction.commit()
    }

    // MARK: - MPV Setup

    private func setupMpv() {
        mpv = mpv_create()
        guard mpv != nil else {
            print("[MPV] Failed to create mpv instance")
            return
        }

        checkError(mpv_request_log_messages(mpv, "warn"), "log-messages")

        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(metalLayer).toOpaque()))
        checkError(mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &wid), "wid")
        checkError(mpv_set_option_string(mpv, "vo", "gpu-next"), "vo")
        checkError(mpv_set_option_string(mpv, "gpu-api", "vulkan"), "gpu-api")
        checkError(mpv_set_option_string(mpv, "gpu-context", "moltenvk"), "gpu-context")
        checkError(mpv_set_option_string(mpv, "hwdec", "auto"), "hwdec-default")
        checkError(mpv_set_option_string(mpv, "audio-channels", "stereo"), "audio-channels")
        checkError(mpv_set_option_string(mpv, "audio-fallback-to-null", "yes"), "audio-fallback-to-null")
        #if targetEnvironment(macCatalyst)
        // MoltenVK does not implement VK_KHR_video_decode_queue, so hwdec=auto
        // falls through to Vulkan video decode and fails with HEVC/AVC streams.
        // Force VideoToolbox directly — Apple's native HW decoder, no Vulkan path.
        checkError(mpv_set_option_string(mpv, "hwdec", "videotoolbox"), "hwdec-mac")
        // mailbox is preferred (non-blocking) but MoltenVK falls back to fifo on
        // most macOS displays — that is fine now that VideoToolbox handles decode.
        checkError(mpv_set_option_string(mpv, "vulkan-swap-mode", "mailbox"), "vulkan-swap-mode-mac")
        // AudioUnit (the default iOS backend) requires AVAudioSession to be
        // activated before init, otherwise it gets 0 output channels and hangs
        // indefinitely.  AVAudioSession is now activated in applicationDidFinish-
        // LaunchingWithOptions (OrientationLockAppDelegate) before any player code
        // runs, so the audiounit backend should receive valid channel info here.
        // If activation failed for some reason, audio-fallback-to-null (set above)
        // will route silently rather than let a broken backend stall the player.
        #else
        checkError(mpv_set_option_string(mpv, "vulkan-swap-mode", "fifo"), "vulkan-swap-mode-ios")
        checkError(mpv_set_option_string(mpv, "vulkan-queue-count", "1"), "vulkan-queue-count")
        #endif
        checkError(mpv_set_option_string(mpv, "vulkan-async-compute", "no"), "vulkan-async-compute")
        checkError(mpv_set_option_string(mpv, "vulkan-async-transfer", "no"), "vulkan-async-transfer")
        // vulkan-disable-interop is not present in this libmpv build — omitted.
        checkError(mpv_set_option_string(mpv, "video-rotate", "no"), "video-rotate")
        // subs-match-os-language / subs-fallback: MPV 0.34+ only; ignore silently if absent.
        mpv_set_option_string(mpv, "subs-match-os-language", "yes")
        mpv_set_option_string(mpv, "subs-fallback", "yes")
        checkError(mpv_set_option_string(mpv, "keep-open", "yes"), "keep-open")
        checkError(mpv_set_option_string(mpv, "target-colorspace-hint", "yes"), "target-colorspace-hint")
        checkError(mpv_set_option_string(mpv, "tone-mapping", "auto"), "tone-mapping")
        checkError(mpv_set_option_string(mpv, "hdr-compute-peak", "yes"), "hdr-compute-peak")

        checkError(mpv_initialize(mpv))

        // Observe properties
        mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "core-idle", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "seeking", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "track-list/count", MPV_FORMAT_INT64)
        mpv_observe_property(mpv, 0, "sub-text", MPV_FORMAT_STRING)

        mpv_set_wakeup_callback(mpv, { ctx in
            let vc = unsafeBitCast(ctx, to: MPVPlayerViewController.self)
            vc.readEvents()
        }, UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()))
    }

    private func setupNotifications() {
        NotificationCenter.default.addObserver(self, selector: #selector(enterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(enterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    // MARK: - Cursor tracking (Mac Catalyst)
    //
    // Design: cursor visibility is fully driven by Kotlin's controls-visible state.
    // – Mouse moves  →  Swift shows cursor immediately + posts NuvioPlayerShowControls
    //                   so Kotlin shows controls and starts its 3.5 s inactivity timer.
    // – Kotlin hides controls (timer fires or user action) → posts NuvioPlayerHideCursor
    //                   → Swift hides cursor at that exact instant.
    // There is no independent Swift-side timer, so cursor and controls always
    // disappear and reappear together.

    #if targetEnvironment(macCatalyst)
    /// Registers the AppKit NSEvent monitor for mouse movement and the
    /// Kotlin-driven NuvioPlayerHideCursor notification listener.
    ///
    /// UIHoverGestureRecognizer is intentionally NOT used: UIKit stops delivering
    /// hover events once the cursor is hidden, making the re-show path unreachable.
    /// NSEvent local monitors bypass UIKit and fire unconditionally.
    private func setupCursorTracking() {
        guard mouseMovedMonitor == nil else { return }

        // NSEventMaskMouseMoved(32) | NSEventMaskLeftMouseDragged(64) | NSEventMaskRightMouseDragged(128)
        let mask = NSNumber(value: UInt64(32 | 64 | 128))
        typealias EventBlock = @convention(block) (AnyObject) -> AnyObject?
        let block: EventBlock = { [weak self] event in
            self?.handleMouseMoved()
            return event
        }
        if let nsEventClass = NSClassFromString("NSEvent") {
            let blockObj = block as AnyObject
            if let result = (nsEventClass as AnyObject).perform(
                NSSelectorFromString("addLocalMonitorForEventsMatchingMask:handler:"),
                with: mask,
                with: blockObj
            ) {
                mouseMovedMonitor = result.takeUnretainedValue()
            }
        }

        // Listen for Kotlin telling us to hide the cursor (controls have been hidden).
        hideCursorObserver = NotificationCenter.default.addObserver(
            forName: Notification.Name("NuvioPlayerHideCursor"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MPVPlayerViewController.setCursorHiddenUntilMouseMoves(true)
        }

        // Ensure cursor starts visible.
        MPVPlayerViewController.setCursorHiddenUntilMouseMoves(false)
    }

    private func handleMouseMoved() {
        // Show the cursor (in case Kotlin had hidden it) and notify Kotlin to
        // reveal player controls and restart its inactivity timer.
        MPVPlayerViewController.setCursorHiddenUntilMouseMoves(false)
        NotificationCenter.default.post(
            name: Notification.Name("NuvioPlayerShowControls"), object: nil)
    }

    func showCursor() {
        MPVPlayerViewController.setCursorHiddenUntilMouseMoves(false)
    }

    private func teardownCursorTracking() {
        if let monitor = mouseMovedMonitor {
            if let nsEventClass = NSClassFromString("NSEvent") {
                _ = (nsEventClass as AnyObject).perform(
                    NSSelectorFromString("removeMonitor:"), with: monitor)
            }
            mouseMovedMonitor = nil
        }
        if let obs = hideCursorObserver {
            NotificationCenter.default.removeObserver(obs)
            hideCursorObserver = nil
        }
        // Always restore the cursor when leaving the player.
        MPVPlayerViewController.setCursorHiddenUntilMouseMoves(false)
    }

    /// Calls AppKit's +[NSCursor setHiddenUntilMouseMoves:(BOOL)] via a C function
    /// pointer obtained from the ObjC runtime.  Using perform:with: fails for
    /// primitive BOOL arguments — this approach passes the value correctly.
    private static func setCursorHiddenUntilMouseMoves(_ hidden: Bool) {
        guard let nsCursorClass = NSClassFromString("NSCursor") else { return }
        let sel = NSSelectorFromString("setHiddenUntilMouseMoves:")
        guard let method = class_getClassMethod(nsCursorClass, sel) else { return }
        typealias SetHiddenFn = @convention(c) (AnyObject, Selector, Bool) -> Void
        let fn = unsafeBitCast(method_getImplementation(method), to: SetHiddenFn.self)
        fn(nsCursorClass as AnyObject, sel, hidden)
    }
    #endif

    @objc private func enterBackground() {
        guard mpv != nil else { return }
        pausePlayback()
        setStringProperty("vid", "no")
    }

    @objc private func enterForeground() {
        guard mpv != nil else { return }
        setStringProperty("vid", "auto")
        playPlayback()
    }

    // MARK: - Playback API

    func loadFile(_ urlString: String, audioUrl: String? = nil, requestHeaders: [String: String] = [:]) {
        let request = PendingLoadRequest(
            urlString: urlString,
            audioUrl: audioUrl,
            requestHeaders: requestHeaders,
            queuedAtUptime: ProcessInfo.processInfo.systemUptime
        )

        if Thread.isMainThread {
            queueLoad(request)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.queueLoad(request)
            }
        }
    }

    private func queueLoad(_ request: PendingLoadRequest) {
        pendingLoadRequest = request
        attemptStartPendingLoad()
    }

    private func attemptStartPendingLoad() {
        guard let request = pendingLoadRequest else { return }
        guard mpv != nil else { return }
        layoutMetalLayer()
        guard isViewportReadyForPlayback(queuedAtUptime: request.queuedAtUptime) else {
            schedulePendingLoadRetry()
            return
        }

        pendingLoadRequest = nil
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        startLoad(request)
    }

    private func startLoad(_ request: PendingLoadRequest) {
        guard mpv != nil else { return }
        layoutMetalLayer()
        clearPlaybackError()
        let sanitizedHeaders = sanitizeRequestHeaders(request.requestHeaders)
        activeRequestHeaders = sanitizedHeaders
        applyRequestHeaders(sanitizedHeaders)
        isPlayerLoading = true
        isPlayerEnded = false
        command("loadfile", args: [request.urlString, "replace"])
        if let audioUrl = request.audioUrl, !audioUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.command("audio-add", args: [audioUrl, "select"], checkForErrors: false)
            }
        }
    }

    private func isViewportReadyForPlayback(queuedAtUptime: TimeInterval) -> Bool {
        guard isViewLoaded, view.window != nil else { return false }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return false }
        if bounds.width >= bounds.height { return true }

        let age = ProcessInfo.processInfo.systemUptime - queuedAtUptime
        return age >= 0.9
    }

    private func schedulePendingLoadRetry() {
        guard pendingLoadRetryWorkItem == nil else { return }

        let workItem = DispatchWorkItem { [weak self] in
            self?.pendingLoadRetryWorkItem = nil
            self?.attemptStartPendingLoad()
        }
        pendingLoadRetryWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: workItem)
    }

    func playPlayback() {
        guard mpv != nil else { return }
        setFlag("pause", false)
    }

    func pausePlayback() {
        guard mpv != nil else { return }
        setFlag("pause", true)
    }

    func seekToMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "absolute"])
    }

    func seekByMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "relative"])
    }

    func retryPlayback() {
        guard mpv != nil else { return }
        if let path = getString("path") {
            clearPlaybackError()
            applyRequestHeaders(activeRequestHeaders)
            let pos = getDouble("time-pos")
            command("loadfile", args: [path, "replace"])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.command("seek", args: [String(format: "%.3f", pos), "absolute"])
            }
        }
    }

    func configureVideoOutput(
        hardwareDecoder: String,
        targetColorspaceHint: Bool,
        toneMapping: String,
        hdrComputePeak: Bool,
        targetPrimaries: String,
        targetTransfer: String,
        extendedDynamicRange: Bool,
        deband: Bool,
        interpolation: Bool,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int
    ) {
        #if !targetEnvironment(macCatalyst)
        // On Mac Catalyst, wantsExtendedDynamicRangeContent is pinned off at init
        // (EDR + MoltenVK causes drawable acquisition failures on non-HDR displays).
        metalLayer.wantsExtendedDynamicRangeContent = extendedDynamicRange
        #endif
        guard mpv != nil else { return }

        #if targetEnvironment(macCatalyst)
        // MoltenVK lacks VK_KHR_video_decode_queue; always keep VideoToolbox on Mac.
        // Ignore whatever hwdec the Kotlin settings layer sends.
        setStringProperty("hwdec", "videotoolbox")
        #else
        setStringProperty("hwdec", hardwareDecoder)
        #endif
        setStringProperty("target-colorspace-hint", targetColorspaceHint ? "yes" : "no")
        setStringProperty("tone-mapping", toneMapping)
        setStringProperty("hdr-compute-peak", hdrComputePeak ? "yes" : "no")
        setStringProperty("target-prim", targetPrimaries)
        setStringProperty("target-trc", targetTransfer)
        setStringProperty("deband", deband ? "yes" : "no")
        setStringProperty("interpolation", interpolation ? "yes" : "no")
        setVideoEqualizer("brightness", brightness)
        setVideoEqualizer("contrast", contrast)
        setVideoEqualizer("saturation", saturation)
        setVideoEqualizer("gamma", gamma)
    }

    func setSpeed(_ speed: Float) {
        guard mpv != nil else { return }
        var s = Double(speed)
        mpv_set_property(mpv, "speed", MPV_FORMAT_DOUBLE, &s)
    }

    func setResize(_ mode: Int) {
        guard mpv != nil else { return }
        switch mode {
        case 1: // Fill
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        case 2: // Zoom
            setStringProperty("panscan", "1.0")
            setStringProperty("video-unscaled", "no")
        default: // Fit
            setStringProperty("panscan", "0.0")
            setStringProperty("video-unscaled", "no")
        }
    }

    // MARK: - Track selection

    func selectAudio(_ trackId: Int) {
        guard mpv != nil else { return }
        var id = Int64(trackId)
        mpv_set_property(mpv, "aid", MPV_FORMAT_INT64, &id)
    }

    func selectSubtitle(_ trackId: Int) {
        guard mpv != nil else { return }
        if trackId < 0 {
            setStringProperty("sid", "no")
        } else {
            var id = Int64(trackId)
            mpv_set_property(mpv, "sid", MPV_FORMAT_INT64, &id)
            // If the UIKit overlay is currently suppressing MPV's renderer
            // (sub-visibility=no) and the user just selected an image-based track
            // (PGS, HDMV, etc.), restore visibility immediately.  applySubtitleStyle
            // is not re-called on track switches, so we must handle it here.
            if isUsingSubtitleOverlay && isSubtitleIdImageBased(mpvId: trackId) {
                setStringProperty("sub-visibility", "yes")
                isUsingSubtitleOverlay = false
                DispatchQueue.main.async { [weak self] in
                    self?.updateSubtitleOverlayText("")
                }
            }
        }
    }

    /// Returns true if the subtitle track with MPV id `mpvId` carries image-based
    /// bitmaps rather than text (PGS, HDMV, DVB, etc.).
    private func isSubtitleIdImageBased(mpvId: Int) -> Bool {
        guard mpv != nil else { return false }
        let count = getInt("track-list/count")
        for i in 0..<count {
            guard (getString("track-list/\(i)/type") ?? "") == "sub" else { continue }
            guard getInt("track-list/\(i)/id") == mpvId else { continue }
            let codec = (getString("track-list/\(i)/codec") ?? "").lowercased()
            return codec.contains("pgs") || codec.contains("hdmv")
                || codec.contains("dvd_subtitle") || codec.contains("dvb_subtitle")
                || codec.contains("hdmv_pgs") || codec.contains("xsub")
        }
        return false
    }

    func addSubtitleUrl(_ url: String) {
        guard mpv != nil else { return }
        command("sub-add", args: [url, "select"])
    }

    func removeExternalSubtitles() {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], checkForErrors: false)
            }
        }
        setStringProperty("sid", "no")
    }

    func removeExternalSubtitlesAndSelect(_ trackId: Int) {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], checkForErrors: false)
            }
        }
        if trackId >= 0 {
            selectSubtitle(trackId)
        } else {
            setStringProperty("sid", "no")
        }
    }

    func setSubtitleDelayMs(_ delayMs: Int) {
        guard mpv != nil else { return }
        var delaySeconds = Double(delayMs) / 1000.0
        mpv_set_property(mpv, "sub-delay", MPV_FORMAT_DOUBLE, &delaySeconds)
    }

    func applySubtitleStyle(textColor: String, backgroundColor: String = "#FF000000", outlineColor: String = "#FF000000", outlineSize: Float, bold: Bool = false, fontSize: Float, subPos: Int, backColor: String, fontName: String, isBold: Bool) {
        guard mpv != nil else { return }

        print("[Nuvio] applySubtitleStyle: text=\(textColor) back=\(backColor) outline=\(outlineColor) font=\(fontName) bold=\(bold || isBold)")

        // On Mac Catalyst, the app runs on a larger display but inherits subtitle
        // settings from the shared iOS/Mac profile.  Apply a scale factor so
        // subtitles are legible on a laptop screen without requiring a separate
        // settings screen.  The user adjusts the iOS size and Mac scales up from it.
        // Stored in UserDefaults so a future Mac preferences panel can override it.
        #if targetEnvironment(macCatalyst)
        let macSubtitleScale: Float = {
            let stored = UserDefaults.standard.float(forKey: "NuvioMacSubtitleScale")
            return stored > 0 ? stored : 2.2
        }()
        let effectiveFontSize = fontSize * macSubtitleScale
        let effectiveOutlineSize = outlineSize * macSubtitleScale
        #else
        let effectiveFontSize = fontSize
        let effectiveOutlineSize = outlineSize
        #endif

        // Decide whether to use the UIKit overlay or MPV's built-in renderer.
        //
        // UIKit overlay: used when there is a non-transparent background OR a
        // custom font family.  It can render real iOS system fonts (SF Mono, etc.)
        // and supports rounded corners + padding — things MPVKit cannot do.
        //
        // MPV renderer: used for the default style (Auto font, no background).
        // MPVKit's bundled libmpv cannot load custom fonts, so we only keep it
        // for the "plain white text, no background" path.
        let bgAlpha = overlayAlpha(fromAARRGGBB: backColor)
        let hasCustomFont = !fontName.isEmpty && fontName != "sans-serif"
        // Image-based subtitles (PGS, HDMV, DVB) have no text content — sub-text
        // is always empty for them.  Always fall through to MPV's native image
        // renderer; the UIKit overlay cannot display them.
        let isImageBased = isSelectedSubtitleImageBased()
        let useOverlay = !isImageBased && (bgAlpha > 0.01 || hasCustomFont)

        isUsingSubtitleOverlay = useOverlay

        if useOverlay {
            // ── UIKit overlay path ──────────────────────────────────────────
            // Font: reverse the ×3 scaling that Kotlin applies to get back to sp/pt.
            let ptSize = max(12, CGFloat(effectiveFontSize) / 3.0)
            let uiFont = subtitleFont(postscriptName: fontName, isBold: isBold || bold, pointSize: ptSize)
            let textUIColor = uiColorRGB(hex: textColor)
            let bgUIColor   = uiColorAARRGGBB(hex: backColor)
            // Map subPos (0..150, 100 = bottom) to a bottom inset in points.
            // subPos=90 (default, bottomOffset=20) → 44 pt from safe-area bottom.
            let bottomInset = CGFloat(24 + max(0, 100 - subPos) * 2)
            // On Mac Catalyst the safe-area bottom is ~0 (no home indicator), so
            // the same inset looks much lower than on iPhone.  Add extra margin to
            // keep subtitles in a comfortable reading position.
            #if targetEnvironment(macCatalyst)
            let effectiveBottomInset = bottomInset + 40.0
            #else
            let effectiveBottomInset = bottomInset
            #endif
            let showOutlineShadow = effectiveOutlineSize > 0

            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.configureSubtitleOverlay(
                    textColor: textUIColor,
                    bgColor: bgUIColor,
                    font: uiFont,
                    bottomInset: effectiveBottomInset,
                    showShadow: showOutlineShadow
                )
                // Show current subtitle immediately (if any).
                let text = self.getString("sub-text") ?? ""
                self.updateSubtitleOverlayText(text)
            }

            // Suppress MPV's own subtitle rendering while the overlay is active.
            setStringProperty("sub-visibility", "no")

        } else {
            // ── MPV native renderer path ────────────────────────────────────
            // Also used for image-based subtitles (PGS/HDMV) regardless of style,
            // since MPV renders them as bitmap overlays rather than text.
            DispatchQueue.main.async { [weak self] in
                self?.updateSubtitleOverlayText("")   // hide overlay
            }
            setStringProperty("sub-visibility", "yes")

            // Text-style properties are irrelevant for image-based subtitle tracks.
            if !isImageBased {
                checkError(mpv_set_property_string(mpv, "sub-ass-override", "strip"))

                let borderStyleErr = mpv_set_property_string(mpv, "sub-border-style", "background-box")
                if borderStyleErr < 0 {
                    checkError(mpv_set_property_string(mpv, "sub-border-style", "opaque-box"))
                }

                checkError(mpv_set_property_string(mpv, "sub-color", textColor))
                checkError(mpv_set_property_string(mpv, "sub-outline-color", outlineColor.isEmpty ? "#FF000000" : outlineColor))

                var outline = Double(effectiveOutlineSize)
                checkError(mpv_set_property(mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline))

                var size = Double(effectiveFontSize)
                checkError(mpv_set_property(mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size))

                // On Mac Catalyst the safe area has no bottom inset, so sub-pos=100
                // (the raw bottom) sits right at the screen edge.  Nudge it up so
                // text subs land in the same comfortable zone as on iPhone.
                #if targetEnvironment(macCatalyst)
                var position = Int64(max(0, subPos - 8))
                #else
                var position = Int64(subPos)
                #endif
                checkError(mpv_set_property(mpv, "sub-pos", MPV_FORMAT_INT64, &position))

                checkError(mpv_set_property_string(mpv, "sub-bold", isBold ? "yes" : "no"))
                checkError(mpv_set_property_string(mpv, "sub-back-color", backColor))
            }
        }
    }

    /// Returns true when the currently selected subtitle track carries image-based
    /// bitmaps (PGS, HDMV, DVB, XSUB) rather than text.  MPV renders these tracks
    /// as pixel overlays; the UIKit text overlay cannot display them at all.
    private func isSelectedSubtitleImageBased() -> Bool {
        guard mpv != nil else { return false }
        let count = getInt("track-list/count")
        for i in 0..<count {
            let type = getString("track-list/\(i)/type") ?? ""
            guard type == "sub" else { continue }
            guard getFlag("track-list/\(i)/selected") else { continue }
            let codec = (getString("track-list/\(i)/codec") ?? "").lowercased()
            return codec.contains("pgs") || codec.contains("hdmv")
                || codec.contains("dvd_subtitle") || codec.contains("dvb_subtitle")
                || codec.contains("hdmv_pgs") || codec.contains("xsub")
        }
        return false
    }

    // MARK: - Subtitle overlay setup & helpers

    private func setupSubtitleOverlay() {
        // Full-screen transparent container (does not intercept touches).
        let container = UIView()
        container.isUserInteractionEnabled = false
        container.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(container)
        NSLayoutConstraint.activate([
            container.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            container.topAnchor.constraint(equalTo: view.topAnchor),
            container.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        // Background pill with rounded corners.
        let bg = UIView()
        bg.layer.cornerRadius = 6
        bg.layer.masksToBounds = true
        bg.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(bg)

        // Label inside the background.
        let label = UILabel()
        label.numberOfLines = 0
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = false
        label.translatesAutoresizingMaskIntoConstraints = false
        bg.addSubview(label)

        // Padding inside the background box.
        let vPad: CGFloat = 6
        let hPad: CGFloat = 12
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: bg.topAnchor, constant: vPad),
            label.bottomAnchor.constraint(equalTo: bg.bottomAnchor, constant: -vPad),
            label.leadingAnchor.constraint(equalTo: bg.leadingAnchor, constant: hPad),
            label.trailingAnchor.constraint(equalTo: bg.trailingAnchor, constant: -hPad),
        ])

        // Constrain background horizontally (centred, max 92% width).
        NSLayoutConstraint.activate([
            bg.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            bg.widthAnchor.constraint(lessThanOrEqualTo: container.widthAnchor, multiplier: 0.92),
        ])

        // Vertical position: anchored to the safe-area bottom, updated per style.
        let bottomConstraint = bg.bottomAnchor.constraint(
            equalTo: container.safeAreaLayoutGuide.bottomAnchor,
            constant: -44  // default: matches subPos=90
        )
        bottomConstraint.isActive = true

        // Start hidden.
        container.isHidden = true

        overlayContainer      = container
        overlayBackground     = bg
        overlayLabel          = label
        overlayBottomConstraint = bottomConstraint
    }

    private func configureSubtitleOverlay(
        textColor: UIColor,
        bgColor: UIColor,
        font: UIFont,
        bottomInset: CGFloat,
        showShadow: Bool
    ) {
        overlayLabel?.textColor = textColor
        overlayLabel?.font = font
        overlayBackground?.backgroundColor = bgColor

        if showShadow {
            overlayLabel?.shadowColor = UIColor.black.withAlphaComponent(0.8)
            overlayLabel?.shadowOffset = CGSize(width: 1, height: 1)
        } else {
            overlayLabel?.shadowColor = .clear
            overlayLabel?.shadowOffset = .zero
        }

        overlayBottomConstraint?.constant = -bottomInset
    }

    private func updateSubtitleOverlayText(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            overlayContainer?.isHidden = true
        } else {
            overlayLabel?.text = trimmed
            overlayContainer?.isHidden = false
        }
    }

    // MARK: - Font helpers

    /// Returns a UIFont for subtitle rendering using real iOS system fonts.
    /// This is what makes SF Mono, Georgia, etc. actually work — unlike MPVKit's
    /// renderer which has no access to the iOS font loader.
    private func subtitleFont(postscriptName: String, isBold: Bool, pointSize: CGFloat) -> UIFont {
        let weight = fontWeight(from: postscriptName, isBold: isBold)

        // Monospace family → SF Mono (system mono), available on iOS 13+.
        if postscriptName.hasPrefix("Menlo") {
            return UIFont.monospacedSystemFont(ofSize: pointSize, weight: weight)
        }

        // Serif family → Georgia (widely available on iOS).
        if postscriptName.hasPrefix("Georgia") {
            let name = weight >= .bold ? "Georgia-Bold" : "Georgia"
            return UIFont(name: name, size: pointSize) ?? UIFont.systemFont(ofSize: pointSize, weight: weight)
        }

        // SansSerif / Auto → system font (SF Pro), which always renders correctly.
        // HelveticaNeue PostScript names map 1:1 but SF Pro looks better on modern iOS.
        return UIFont.systemFont(ofSize: pointSize, weight: weight)
    }

    private func fontWeight(from postscriptName: String, isBold: Bool) -> UIFont.Weight {
        let lower = postscriptName.lowercased()
        if lower.contains("thin")                          { return .thin }
        if lower.contains("ultralight") || lower.contains("extralight") { return .ultraLight }
        if lower.contains("light")                         { return .light }
        if lower.contains("medium")                        { return .medium }
        if lower.contains("semibold")                      { return .semibold }
        if lower.contains("condensedblack") || lower.contains("heavy") { return .heavy }
        if lower.contains("bold") || isBold                { return .bold }
        return .regular
    }

    // MARK: - Colour helpers (for the UIKit overlay)

    /// Parse "#RRGGBB" → UIColor (alpha=1).
    private func uiColorRGB(hex: String) -> UIColor {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard s.count == 6, let rgb = UInt64(s, radix: 16) else { return .white }
        return UIColor(
            red:   CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >>  8) & 0xFF) / 255,
            blue:  CGFloat( rgb        & 0xFF) / 255,
            alpha: 1
        )
    }

    /// Parse "#AARRGGBB" (MPV format, alpha first) → UIColor with alpha baked in.
    private func uiColorAARRGGBB(hex: String) -> UIColor {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard s.count == 8, let argb = UInt64(s, radix: 16) else {
            return UIColor.black.withAlphaComponent(0)
        }
        let a = CGFloat((argb >> 24) & 0xFF) / 255
        let r = CGFloat((argb >> 16) & 0xFF) / 255
        let g = CGFloat((argb >>  8) & 0xFF) / 255
        let b = CGFloat( argb        & 0xFF) / 255
        return UIColor(red: r, green: g, blue: b, alpha: a)
    }

    /// Extract just the alpha byte from an "#AARRGGBB" string.
    private func overlayAlpha(fromAARRGGBB hex: String) -> CGFloat {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard s.count == 8, let argb = UInt64(s, radix: 16) else { return 0 }
        return CGFloat((argb >> 24) & 0xFF) / 255
    }

    func destroyPlayer() {
        #if targetEnvironment(macCatalyst)
        teardownCursorTracking()
        #endif
        NotificationCenter.default.removeObserver(self)
        pendingLoadRetryWorkItem?.cancel()
        pendingLoadRetryWorkItem = nil
        pendingLoadRequest = nil
        clearPlaybackError()
        isUsingSubtitleOverlay = false
        overlayContainer?.isHidden = true
        guard let ctx = mpv else { return }
        mpv = nil  // nil first so event loop stops reading
        mpv_terminate_destroy(ctx)
    }

    // MARK: - State Update

    /// Lightweight state refresh — called by Kotlin polling (every 250ms).
    /// Only reads cheap scalar properties; does NOT re-enumerate tracks.
    func refreshPlaybackState() {
        guard mpv != nil else { return }
        let duration = getDouble("duration")
        let position = getDouble("time-pos")
        let cached = getDouble("demuxer-cache-time")
        let speed = getDouble("speed")
        let paused = getFlag("pause")
        let eofReached = getFlag("eof-reached")
        let idle = getFlag("core-idle")
        let seeking = getFlag("seeking")
        let bufferingCache = getFlag("paused-for-cache")

        isPlayerLoading = (idle && !paused && !eofReached) || seeking || bufferingCache
        isPlayerPlaying = !paused && !idle && !eofReached
        isPlayerEnded = eofReached
        durationMs = Int64(duration * 1000)
        positionMs = Int64(max(position, 0) * 1000)
        bufferedMs = Int64(max(position + cached, 0) * 1000)
        currentSpeed = Float(speed > 0 ? speed : 1.0)
    }

    /// Full state + track refresh — called from MPV event loop on property changes.
    func updateState() {
        refreshPlaybackState()
        refreshTracks()
    }

    private func refreshTracks() {
        guard mpv != nil else { return }
        var audio = [TrackInfo]()
        var subs = [TrackInfo]()
        let count = getInt("track-list/count")
        var audioIdx = 0
        var subIdx = 0
        // Track whether the currently selected sub track is image-based.
        // Used after the loop to fix up overlay suppression without extra queries.
        var selectedSubIsImageBased = false

        for i in 0..<count {
            let type = getString("track-list/\(i)/type") ?? ""
            let id = getInt("track-list/\(i)/id")
            let title = getTrackString(i, "title")
            let lang = getTrackString(i, "lang")
            let codec = getTrackString(i, "codec")
            let decoderDescription = getTrackString(i, "decoder-desc")
            let channels = getTrackString(i, "demux-channels")
            let channelCount = getInt("track-list/\(i)/demux-channel-count")
            let selected = getFlag("track-list/\(i)/selected")
            let displayTitle = formatTrackTitle(
                type: type,
                index: type == "audio" ? audioIdx : subIdx,
                title: title,
                lang: lang,
                codec: codec,
                decoderDescription: decoderDescription,
                channels: channels,
                channelCount: channelCount
            )

            if type == "audio" {
                audio.append(TrackInfo(index: audioIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                audioIdx += 1
            } else if type == "sub" {
                subs.append(TrackInfo(index: subIdx, id: id, type: type, title: displayTitle, lang: lang, selected: selected))
                subIdx += 1
                if selected {
                    let c = codec.lowercased()
                    selectedSubIsImageBased = c.contains("pgs") || c.contains("hdmv")
                        || c.contains("dvd_subtitle") || c.contains("dvb_subtitle")
                        || c.contains("hdmv_pgs") || c.contains("xsub")
                }
            }
        }
        audioTracks = audio
        subtitleTracks = subs

        // Safety net: if the UIKit overlay is suppressing MPV's renderer but the
        // auto-selected subtitle track is image-based (e.g. PGS via subs-match-os-
        // language), restore visibility immediately so bitmap frames are not hidden.
        if isUsingSubtitleOverlay && selectedSubIsImageBased {
            setStringProperty("sub-visibility", "yes")
            isUsingSubtitleOverlay = false
            DispatchQueue.main.async { [weak self] in
                self?.updateSubtitleOverlayText("")
            }
        }
    }

    private func getTrackString(_ index: Int, _ field: String) -> String {
        (getString("track-list/\(index)/\(field)") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func formatTrackTitle(
        type: String,
        index: Int,
        title: String,
        lang: String,
        codec: String,
        decoderDescription: String,
        channels: String,
        channelCount: Int
    ) -> String {
        let base = ifNotBlank(title)
            ?? localizedLanguageName(lang)
            ?? (type == "sub" ? "Subtitle \(index + 1)" : "Track \(index + 1)")
        let codecName = codecDisplayName(codec) ?? codecDisplayName(decoderDescription)
        let channelName = type == "audio" ? channelLayoutName(channels: channels, channelCount: channelCount) : nil
        let details = [channelName, codecName]
            .compactMap { $0 }
            .filter { detail in !base.localizedCaseInsensitiveContains(detail) }
        return details.isEmpty ? base : "\(base) (\(details.joined(separator: ", ")))"
    }

    private func ifNotBlank(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func localizedLanguageName(_ languageCode: String) -> String? {
        guard let code = ifNotBlank(languageCode) else { return nil }
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private func channelLayoutName(channels: String, channelCount: Int) -> String? {
        if let normalized = ifNotBlank(channels), normalized != "unknown" {
            let lower = normalized.lowercased()
            if lower == "mono" { return "Mono" }
            if lower == "stereo" { return "Stereo" }
            return normalized
        }
        switch channelCount {
        case 1:
            return "Mono"
        case 2:
            return "Stereo"
        case 6:
            return "5.1"
        case 8:
            return "7.1"
        case let count where count > 0:
            return "\(count)ch"
        default:
            return nil
        }
    }

    private func codecDisplayName(_ value: String) -> String? {
        guard let raw = ifNotBlank(value) else { return nil }
        let codec = raw.lowercased()
        if codec.contains("eac3") || codec.contains("e-ac-3") || codec.contains("e ac-3") {
            return codec.contains("joc") || codec.contains("atmos") ? "E-AC-3-JOC" : "E-AC-3"
        }
        if codec.contains("truehd") || codec.contains("true hd") { return "TrueHD" }
        if codec.contains("ac3") || codec.contains("ac-3") { return "AC-3" }
        if codec.contains("dts-hd") || codec.contains("dtshd") || codec.contains("dts hd") { return "DTS-HD" }
        if codec.contains("dts") || codec == "dca" { return "DTS" }
        if codec.contains("aac") { return "AAC" }
        if codec.contains("mp3") || codec.contains("mpeg audio") { return "MP3" }
        if codec.contains("mp2") { return "MP2" }
        if codec.contains("opus") { return "Opus" }
        if codec.contains("vorbis") { return "Vorbis" }
        if codec.contains("flac") { return "FLAC" }
        if codec.contains("alac") { return "ALAC" }
        if codec.contains("pcm") || codec.contains("wav") { return "WAV" }
        if codec.contains("amr_wb") || codec.contains("amr-wb") { return "AMR-WB" }
        if codec.contains("amr_nb") || codec.contains("amr-nb") { return "AMR-NB" }
        if codec.contains("amr") { return "AMR" }
        if codec.contains("iamf") { return "IAMF" }
        if codec.contains("mpegh") || codec.contains("mpeg-h") { return "MPEG-H" }
        if codec.contains("pgs") || codec.contains("hdmv") { return "PGS" }
        if codec.contains("subrip") || codec == "srt" { return "SRT" }
        if codec.contains("ass") || codec.contains("ssa") { return "SSA" }
        if codec.contains("webvtt") || codec == "vtt" { return "VTT" }
        if codec.contains("ttml") { return "TTML" }
        if codec.contains("mov_text") || codec.contains("tx3g") { return "TX3G" }
        if codec.contains("dvb") { return "DVB" }
        return raw
    }

    private func clearPlaybackError() {
        errorStateLock.lock()
        recentPlaybackLogs.removeAll(keepingCapacity: true)
        _currentErrorMessage = nil
        errorStateLock.unlock()
    }

    private func appendPlaybackLog(prefix: String, level: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard level == "warn" || level == "error" || level == "fatal" else { return }

        let formatted = "[\(prefix)] \(trimmed)"
        errorStateLock.lock()
        recentPlaybackLogs.append(formatted)
        if recentPlaybackLogs.count > 4 {
            recentPlaybackLogs.removeFirst(recentPlaybackLogs.count - 4)
        }
        errorStateLock.unlock()
    }

    private func setPlaybackError(_ fallback: String) {
        let trimmedFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        errorStateLock.lock()
        var parts = recentPlaybackLogs.suffix(3)
        if !trimmedFallback.isEmpty && !parts.contains(trimmedFallback) {
            parts.append(trimmedFallback)
        }
        _currentErrorMessage = parts.isEmpty ? "Unable to play this stream." : parts.joined(separator: "\n")
        errorStateLock.unlock()
    }

    // MARK: - Event Loop

    private func readEvents() {
        eventQueue.async { [weak self] in
            guard let self, let mpv = self.mpv else { return }

            while true {
                let event = mpv_wait_event(mpv, 0)
                guard let eventPtr = event else { break }
                if eventPtr.pointee.event_id == MPV_EVENT_NONE { break }

                switch eventPtr.pointee.event_id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    // If the overlay is active, grab sub-text on the event thread
                    // (mpv_get_property_string is thread-safe) so we can update the
                    // UIKit label on the main thread with zero additional delay.
                    let overlayText: String? = self.isUsingSubtitleOverlay
                        ? (self.getString("sub-text") ?? "")
                        : nil
                    DispatchQueue.main.async {
                        self.updateState()
                        if let text = overlayText {
                            self.updateSubtitleOverlayText(text)
                        }
                    }
                case MPV_EVENT_FILE_LOADED:
                    DispatchQueue.main.async {
                        self.clearPlaybackError()
                        self.isPlayerLoading = false
                        self.updateState()
                    }
                case MPV_EVENT_END_FILE:
                    if let data = eventPtr.pointee.data {
                        let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(data)).pointee
                        if endFile.reason == MPV_END_FILE_REASON_ERROR {
                            let errorText = String(cString: mpv_error_string(endFile.error))
                            self.setPlaybackError("[mpv] \(errorText)")
                            print("[MPV] End file error: \(errorText)")
                        }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                case MPV_EVENT_LOG_MESSAGE:
                    if let msg = UnsafeMutablePointer<mpv_event_log_message>(OpaquePointer(eventPtr.pointee.data)) {
                        let prefix = String(cString: msg.pointee.prefix!)
                        let level = String(cString: msg.pointee.level!)
                        let text = String(cString: msg.pointee.text!)
                        self.appendPlaybackLog(prefix: prefix, level: level, text: text)
                        print("[MPV][\(prefix)] \(level): \(text)", terminator: "")
                    }
                default:
                    break
                }
            }
        }
    }

    // MARK: - MPV Helpers

    private func command(_ command: String, args: [String?] = [], checkForErrors: Bool = true) {
        guard mpv != nil else { return }
        var cargs = makeCArgs(command, args).map { $0.flatMap { UnsafePointer<CChar>(strdup($0)) } }
        defer { for ptr in cargs where ptr != nil { free(UnsafeMutablePointer(mutating: ptr!)) } }
        let ret = mpv_command(mpv, &cargs)
        if checkForErrors { checkError(ret) }
    }

    private func makeCArgs(_ command: String, _ args: [String?]) -> [String?] {
        var strArgs = args
        strArgs.insert(command, at: 0)
        strArgs.append(nil)
        return strArgs
    }

    private func getDouble(_ name: String) -> Double {
        guard mpv != nil else { return 0.0 }
        var data = Double()
        mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &data)
        return data
    }

    private func getString(_ name: String) -> String? {
        guard mpv != nil else { return nil }
        let cstr = mpv_get_property_string(mpv, name)
        let str: String? = cstr == nil ? nil : String(cString: cstr!)
        mpv_free(cstr)
        return str
    }

    private func getFlag(_ name: String) -> Bool {
        guard mpv != nil else { return false }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &data)
        return data > 0
    }

    private func setFlag(_ name: String, _ flag: Bool) {
        guard mpv != nil else { return }
        var data: Int = flag ? 1 : 0
        mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &data)
    }

    private func setStringProperty(_ name: String, _ value: String) {
        guard mpv != nil else { return }
        checkError(mpv_set_property_string(mpv, name, value))
    }

    private func setVideoEqualizer(_ name: String, _ value: Int) {
        guard mpv != nil else { return }
        var clamped = Int64(max(-100, min(100, value)))
        checkError(mpv_set_property(mpv, name, MPV_FORMAT_INT64, &clamped))
    }

    private func getInt(_ name: String) -> Int {
        guard mpv != nil else { return 0 }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_INT64, &data)
        return Int(data)
    }

    @discardableResult
    private func checkError(_ status: CInt, _ context: String = "") -> CInt {
        if status < 0 {
            let detail = context.isEmpty ? "" : " [\(context)]"
            print("[MPV] API error\(detail): \(String(cString: mpv_error_string(status)))")
        }
        return status
    }

    private func sanitizeRequestHeaders(_ headers: [String: String]) -> [String: String] {
        guard !headers.isEmpty else { return [:] }

        var sanitized: [String: String] = [:]
        sanitized.reserveCapacity(headers.count)
        headers.forEach { rawKey, rawValue in
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !value.isEmpty else { return }
            guard key.caseInsensitiveCompare("Range") != .orderedSame else { return }
            sanitized[key] = value
        }
        return sanitized
    }

    private func applyRequestHeaders(_ headers: [String: String]) {
        guard mpv != nil else { return }
        if headers.isEmpty {
            checkError(mpv_set_property_string(mpv, "http-header-fields", ""))
            return
        }

        let serialized = headers
            .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { key, value in
                let escapedValue = value
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: ",", with: "\\,")
                return "\(key): \(escapedValue)"
            }
            .joined(separator: ",")
        checkError(mpv_set_property_string(mpv, "http-header-fields", serialized))
    }

    private func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()

        var currentParent = parent
        while let controller = currentParent {
            controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
            controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
            controller.setNeedsStatusBarAppearanceUpdate()
            if let rootController = controller as? RootComposeViewController {
                rootController.refreshImmersiveSystemUI()
            }
            currentParent = controller.parent
        }
    }
}

// MARK: - Bridge Creator (implements Kotlin protocol)

final class MPVPlayerBridgeCreator: NSObject, NuvioPlayerBridgeCreator {
    func createBridge() -> any NuvioPlayerBridge {
        return MPVPlayerBridgeImpl()
    }
}

// MARK: - Registration (called from Swift app startup)

enum NuvioPlayerRegistration {
    static func register() {
        NuvioPlayerBridgeFactory.shared.registerFactory(creator: MPVPlayerBridgeCreator())
    }
}
