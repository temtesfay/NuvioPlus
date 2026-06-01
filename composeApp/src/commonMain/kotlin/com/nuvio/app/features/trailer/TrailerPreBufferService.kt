package com.nuvio.app.features.trailer

/**
 * Platform-agnostic pre-buffer service.
 *
 * On iOS the registered provider creates an AVPlayerItem immediately when a
 * trailer URL is resolved — triggering the HLS manifest download and first-segment
 * buffering in the background. By the time the user swipes to that slide, AVPlayer
 * only needs to render the first frame (< 1s) rather than downloading everything
 * from scratch (3-6s).
 *
 * On other platforms the calls are no-ops (no provider registered).
 *
 * Usage:
 *   1. iOS app calls [register] at startup (before any trailer resolution).
 *   2. Pre-warm calls [prefetch] as soon as each URL is resolved.
 *   3. Swift bridge init calls [HeroTrailerPreBufferCache.consume] to get the
 *      already-buffered AVPlayerItem instead of creating a cold one.
 */
interface TrailerPreBufferProvider {
    /**
     * Begin pre-buffering [videoUrl] immediately.
     * [audioUrl] is non-null only for the rare split-stream (DASH) path;
     * for HLS muxed streams it is null.
     * Idempotent — calling with the same URL multiple times is safe.
     */
    fun prefetch(videoUrl: String, audioUrl: String?)
}

object TrailerPreBufferService {
    private var provider: TrailerPreBufferProvider? = null

    /** Called once at app startup by the platform layer (e.g. NuvioHeroPlayerRegistration). */
    fun register(provider: TrailerPreBufferProvider) {
        this.provider = provider
    }

    /**
     * Kick off background buffering for [videoUrl].
     * No-op if no provider has been registered (non-iOS platforms).
     */
    fun prefetch(videoUrl: String, audioUrl: String?) {
        provider?.prefetch(videoUrl, audioUrl)
    }
}
