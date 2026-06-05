import AVFoundation
import ComposeApp
import UIKit

// MARK: - Pre-buffer cache

/// Caches AVURLAssets created as soon as trailer URLs are resolved by the Kotlin pre-warm.
/// Loading the asset's "playable" key triggers the HLS manifest download immediately.
///
/// Unlike an AVPlayerItem, an AVURLAsset is reusable — a new AVPlayerItem can be created
/// from the same asset on every slide visit. The asset retains the downloaded manifest in
/// memory, so each new item skips the manifest fetch (~150-300ms) even when swiping back
/// to a previously-visited slide. This fixes the 3-6s cold-start on swipe-back.
///
/// All methods are dispatched to the main thread to stay in sync with AVFoundation's
/// threading expectations and with bridge init (which also runs on the main thread).
final class HeroTrailerPreBufferCache {
    static let shared = HeroTrailerPreBufferCache()
    private var assets: [String: AVURLAsset] = [:]
    // Strong references to chunked loaders — AVURLAsset holds only a weak reference
    // to its resource loader delegate. Without this the loader is released immediately
    // and AVFoundation falls back to direct (throttled) fetching.
    private var loaders: [String: YouTubeChunkedResourceLoader] = [:]
    private init() {}

    /// Create (and start pre-loading) an AVURLAsset for [url].
    /// For YouTube CDN URLs (googlevideo.com) the asset is backed by a chunked loader
    /// that bypasses server-side throttling. Safe to call multiple times — no-op if cached.
    func prefetch(url urlString: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard self.assets[urlString] == nil else { return }

            let asset: AVURLAsset
            if let (chunkedAsset, loader) = YouTubeChunkedResourceLoader.makeAsset(for: urlString) {
                asset = chunkedAsset
                self.loaders[urlString] = loader
            } else {
                guard let url = URL(string: urlString) else { return }
                asset = AVURLAsset(url: url)
            }
            self.assets[urlString] = asset
            // Kick off the network probe. For HLS this downloads the .m3u8 manifest;
            // for chunked YouTube URLs the resource loader fires a Range: bytes=0-1 request
            // to resolve MIME type and content length. Both happen before the bridge is
            // created so AVPlayer can jump straight to buffering the first segment.
            asset.loadValuesAsynchronously(forKeys: ["playable"]) { }
        }
    }

    /// Create a new AVPlayerItem from the cached asset for [url], or nil if not cached.
    /// Does NOT remove the asset — the same asset can serve multiple bridge instances so
    /// swiping back to a slide is just as fast as the first visit.
    func makeItem(url urlString: String) -> AVPlayerItem? {
        // Already on main thread (called from bridge init during Compose recomposition).
        guard let asset = assets[urlString] else { return nil }
        return AVPlayerItem(asset: asset)
    }

    /// Clear all cached assets (e.g. on logout / session reset).
    func clearAll() {
        DispatchQueue.main.async {
            self.assets.removeAll()
            self.loaders.removeAll()
        }
    }
}

// MARK: - Pre-buffer provider (registered with Kotlin TrailerPreBufferService)

/// Kotlin calls [prefetch] via [TrailerPreBufferService] as soon as each URL
/// is resolved in the pre-warm. This bridges into [HeroTrailerPreBufferCache].
final class NuvioTrailerPreBufferProvider: TrailerPreBufferProvider {
    func prefetch(videoUrl: String, audioUrl: String?) {
        HeroTrailerPreBufferCache.shared.prefetch(url: videoUrl)
        // Pre-buffer the audio stream too. For split-stream slides the audio player
        // always started cold (manifest download blocked readyToPlay by 1-3 s). Now
        // both AVURLAssets load their manifests in parallel during the pre-warm.
        if let audioUrl = audioUrl {
            HeroTrailerPreBufferCache.shared.prefetch(url: audioUrl)
        }
    }
}

// MARK: - HeroPlayerView

/// UIView subclass that hosts an AVPlayerLayer, filling it on every layout pass.
private final class NuvioHeroPlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    override init(frame: CGRect) {
        super.init(frame: frame)
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        playerLayer.videoGravity = .resizeAspectFill
        clipsToBounds = true
        isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private var pinnedSuperview: UIView?

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        guard let parent = superview, parent !== pinnedSuperview else { return }
        pinnedSuperview = parent
        frame = parent.bounds
        playerLayer.frame = bounds
        translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            trailingAnchor.constraint(equalTo: parent.trailingAnchor),
            topAnchor.constraint(equalTo: parent.topAnchor),
            bottomAnchor.constraint(equalTo: parent.bottomAnchor),
        ])
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}

// MARK: - NuvioHeroPlayerBridgeImpl

/// Swift implementation of the Kotlin HeroPlayerBridge interface.
///
/// One bridge (and one AVPlayer) per slide. The Kotlin side uses key(settledPage)
/// around HeroVideoSurface so this bridge's UIView is always freshly placed in the
/// UIKit hierarchy — ensuring isReadyForDisplay / laidOut fire correctly.
///
/// Audio is deferred until the first video frame is confirmed visible (markReady)
/// so the muxed audio track cannot bleed out over the poster.
final class NuvioHeroPlayerBridgeImpl: HeroPlayerBridge {

    private let videoPlayer: AVPlayer
    private let videoItem: AVPlayerItem
    private let audioPlayer: AVPlayer?
    private let audioItem: AVPlayerItem?
    private let view: NuvioHeroPlayerView
    private var endObserver: Any?
    private var syncTimer: Timer?
    private let driftThresholdSeconds: Double = 0.30
    private var didStartAudio: Bool = false
    /// Latest mute preference. Applied in markReady() so audio never plays before
    /// the first video frame is visible.
    private var desiredMute: Bool = true
    // Strong references to chunked loaders for any assets created outside the
    // pre-buffer cache (cache miss on first launch). AVURLAsset holds the delegate weakly.
    private var chunkedLoaders: [YouTubeChunkedResourceLoader] = []

    private var _ready = false
    private var _failed = false
    /// Epoch-ms when the video time first crossed 0.05s — used for the
    /// isReadyForDisplay grace period.
    private var timeAdvancingFirstSeenMs: Double = 0

    init(videoUrl: String, audioUrl: String?, onEnded: @escaping () -> Void) {
        guard let vUrl = URL(string: videoUrl) else {
            videoItem = AVPlayerItem(url: URL(string: "about:blank")!)
            videoPlayer = AVPlayer()
            audioItem = nil
            audioPlayer = nil
            view = NuvioHeroPlayerView()
            _failed = true
            return
        }

        // Collect loaders in a local so we don't touch `self` until all stored
        // properties are initialized (Swift two-phase init requirement).
        var initLoaders: [YouTubeChunkedResourceLoader] = []

        // Use the pre-buffered asset if the pre-warm already ran for this URL.
        // On a cache miss (e.g. very first launch before pre-warm finishes), create a
        // fresh chunked asset so we still bypass YouTube CDN throttling.
        if let cached = HeroTrailerPreBufferCache.shared.makeItem(url: videoUrl) {
            videoItem = cached
        } else if let (chunkedAsset, loader) = YouTubeChunkedResourceLoader.makeAsset(for: videoUrl) {
            videoItem = AVPlayerItem(asset: chunkedAsset)
            initLoaders.append(loader)
        } else {
            videoItem = AVPlayerItem(url: vUrl)
        }
        videoPlayer = AVPlayer(playerItem: videoItem)
        // Keep muted until markReady() confirms the first frame is visible.
        // This prevents the muxed audio track from bleeding out over the poster.
        videoPlayer.isMuted = true
        videoPlayer.volume = 0
        // Don't wait for an "optimal" network buffer before starting. For muted
        // background trailers a brief stall is acceptable; faster first-frame
        // matters more. Without this, AVPlayer sits buffering for 3-5s before
        // starting, which is most of the "10s" startup the user sees.
        videoPlayer.automaticallyWaitsToMinimizeStalling = false

        if let aUrlString = audioUrl, let aUrl = URL(string: aUrlString) {
            // Same cache-first / chunked-fallback logic as video.
            let aItem: AVPlayerItem
            if let cached = HeroTrailerPreBufferCache.shared.makeItem(url: aUrlString) {
                aItem = cached
            } else if let (chunkedAsset, loader) = YouTubeChunkedResourceLoader.makeAsset(for: aUrlString) {
                aItem = AVPlayerItem(asset: chunkedAsset)
                initLoaders.append(loader)
            } else {
                aItem = AVPlayerItem(url: aUrl)
            }
            let aPlayer = AVPlayer(playerItem: aItem)
            aPlayer.isMuted = true
            aPlayer.volume = 0
            // Mirror the video player setting so audio doesn't stall waiting for an
            // "optimal" buffer — both streams start rendering as fast as possible.
            aPlayer.automaticallyWaitsToMinimizeStalling = false
            audioItem = aItem
            audioPlayer = aPlayer
        } else {
            audioItem = nil
            audioPlayer = nil
        }

        view = NuvioHeroPlayerView()
        view.playerLayer.player = videoPlayer

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: videoItem,
            queue: .main
        ) { _ in onEnded() }

        videoPlayer.play()

        if audioPlayer != nil {
            startSyncTimer()
        }

        // Phase 2 — all stored properties are now initialized; safe to assign to self.
        chunkedLoaders = initLoaders
    }

    deinit {
        syncTimer?.invalidate()
        if let token = endObserver {
            NotificationCenter.default.removeObserver(token)
        }
    }

    // MARK: - Sync (split-stream path only)

    private func startSyncTimer() {
        syncTimer?.invalidate()
        syncTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if self.didStartAudio {
                self.resyncAudioIfDrifting()
            } else {
                self.maybeStartAudio()
            }
        }
        if let timer = syncTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
    }

    private func resyncAudioIfDrifting() {
        guard let aPlayer = audioPlayer, let aItem = audioItem else { return }
        guard videoItem.status == .readyToPlay, aItem.status == .readyToPlay else { return }
        guard videoPlayer.rate > 0 else { return }

        let videoSeconds = CMTimeGetSeconds(videoPlayer.currentTime())
        let audioSeconds = CMTimeGetSeconds(aPlayer.currentTime())
        guard videoSeconds.isFinite, audioSeconds.isFinite else { return }

        let drift = audioSeconds - videoSeconds
        if abs(drift) > driftThresholdSeconds {
            aPlayer.seek(
                to: videoPlayer.currentTime(),
                toleranceBefore: .zero,
                toleranceAfter: .zero
            ) { [weak aPlayer] _ in
                if aPlayer?.rate == 0 { aPlayer?.play() }
            }
        }
    }

    private func maybeStartAudio() {
        guard !didStartAudio else { return }
        guard let aPlayer = audioPlayer, let aItem = audioItem else { return }
        guard videoItem.status == .readyToPlay, aItem.status == .readyToPlay else { return }

        aPlayer.isMuted = desiredMute
        aPlayer.volume = desiredMute ? 0 : 1
        aPlayer.seek(
            to: videoPlayer.currentTime(),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        ) { [weak self] _ in
            aPlayer.play()
            self?.didStartAudio = true
        }
    }

    // MARK: - HeroPlayerBridge

    func createView() -> UIView { view }

    func release() {
        syncTimer?.invalidate()
        syncTimer = nil
        videoPlayer.pause()
        videoPlayer.replaceCurrentItem(with: nil)
        audioPlayer?.pause()
        audioPlayer?.replaceCurrentItem(with: nil)
        if let token = endObserver {
            NotificationCenter.default.removeObserver(token)
            endObserver = nil
        }
    }

    func isReady() -> Bool {
        if _ready { return true }
        let statusReady = videoItem.status == .readyToPlay
        let layerReady = view.playerLayer.isReadyForDisplay
        let timeAdvancing = CMTimeGetSeconds(videoPlayer.currentTime()) > 0.05
        let ourBoundsOk = view.bounds.width > 0 && view.bounds.height > 0
        let parentBoundsOk = (view.superview?.bounds.width ?? 0) > 0
        let laidOut = ourBoundsOk || parentBoundsOk

        // Primary path: all signals confirmed.
        if statusReady && layerReady && timeAdvancing && laidOut {
            markReady()
            return true
        }

        // Relaxed fallback: isReadyForDisplay occasionally lags behind the actual
        // first frame on some HLS streams. If the video has been advancing for
        // 1.5s the decoder is clearly outputting frames — force ready rather than
        // waiting the full 5s that was causing the "10 second" startup the user sees.
        if statusReady && timeAdvancing && laidOut {
            let nowMs = Date().timeIntervalSince1970 * 1000
            if timeAdvancingFirstSeenMs == 0 {
                timeAdvancingFirstSeenMs = nowMs
            } else if nowMs - timeAdvancingFirstSeenMs > 1_500 {
                NSLog("NuvioHeroPlayer: isReadyForDisplay never fired after 1.5s — forcing ready")
                markReady()
                return true
            }
        }

        return false
    }

    /// Called the first time the player is confirmed ready. Applies the stored mute
    /// preference so audio cannot play before the first video frame is visible.
    private func markReady() {
        guard !_ready else { return }
        _ready = true
        if audioPlayer == nil {
            videoPlayer.isMuted = desiredMute
            videoPlayer.volume = desiredMute ? 0 : 1
        }
    }

    func isFailed() -> Bool {
        if _failed { return true }
        _failed = (videoItem.status == .failed || videoPlayer.status == .failed)
        return _failed
    }

    func setMuted(muted: Bool) {
        desiredMute = muted

        if !muted {
            #if !targetEnvironment(macCatalyst)
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .moviePlayback, options: [])
                try session.setActive(true, options: [])
            } catch {
                NSLog("NuvioHeroPlayer: failed to activate audio session: \(error)")
            }
            #endif
        }

        if let aPlayer = audioPlayer, didStartAudio {
            // Split-stream: audio lives in its own player
            aPlayer.isMuted = muted
            aPlayer.volume = muted ? 0 : 1
            videoPlayer.isMuted = true
            videoPlayer.volume = 0
        } else if audioPlayer == nil {
            // Muxed path: only apply once video is confirmed visible (markReady).
            // Until then, audio is deferred to prevent the "audio over poster" issue.
            if _ready {
                videoPlayer.isMuted = muted
                videoPlayer.volume = muted ? 0 : 1
            }
        }
    }
}

// MARK: - Factory

final class NuvioHeroPlayerBridgeCreator: HeroPlayerBridgeCreator {
    func create(
        videoUrl: String,
        audioUrl: String?,
        onEnded: @escaping () -> Void
    ) -> any HeroPlayerBridge {
        NuvioHeroPlayerBridgeImpl(videoUrl: videoUrl, audioUrl: audioUrl, onEnded: onEnded)
    }
}

// MARK: - Registration entry point (called from ContentView.swift)

enum NuvioHeroPlayerRegistration {
    static func register() {
        HeroPlayerBridgeFactory.shared.registerFactory(creator: NuvioHeroPlayerBridgeCreator())
        // Register the pre-buffer provider so Kotlin's TrailerPreBufferService can
        // kick off AVPlayerItem loading as soon as each trailer URL is resolved.
        TrailerPreBufferService.shared.register(provider: NuvioTrailerPreBufferProvider())
        // Pre-warm AVFoundation before the user reaches the home screen.
        // Creating a silent AVPlayer on a background thread forces AVFoundation's
        // audio/video subsystem to initialise during profile selection rather than
        // during the first hero-trailer bridge creation. This drops "Bridge ready"
        // latency from ~1900 ms (cold) to ~100-200 ms for the first slide.
        prewarmAVFoundation()
    }

    private static func prewarmAVFoundation() {
        DispatchQueue.global(qos: .userInitiated).async {
            // Instantiating AVPlayer triggers lazy subsystem init (audio session
            // config, VideoToolbox setup, etc.). Releasing it immediately afterwards
            // keeps memory clean — the initialisation state is retained process-wide.
            let warmupPlayer = AVPlayer()
            // A brief pause lets the subsystem fully spin up before we release the
            // reference. Without it the initialisation would be cut short.
            Thread.sleep(forTimeInterval: 0.08)
            _ = warmupPlayer
        }
    }
}
