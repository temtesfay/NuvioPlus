package com.nuvio.app.features.trailer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches resolved [TrailerPlaybackSource]s by YouTube video key so the YouTube
 * extraction (the slow part of trailer startup — 2-5 s) only happens once per
 * video per session.
 *
 * Used by both the lazy on-demand path (when the user lands on a slide) and
 * the eager pre-warming path (background pre-resolution of all hero items so
 * subsequent slides feel instant).
 *
 * Note: YouTube playback URLs contain an `expire` query param (usually 6 h),
 * so this cache is intentionally process-lifetime only — restarting the app
 * gets fresh URLs.
 */
object HeroTrailerSourceCache {
    private val cache = mutableMapOf<String, TrailerPlaybackSource?>()
    // Track in-flight resolutions so concurrent callers for the same key don't
    // each fire their own extraction.
    private val inflight = mutableMapOf<String, Mutex>()
    private val mapLock = Mutex()

    /**
     * Returns a cached source for [youtubeKey] if one exists, otherwise resolves
     * via the platform extractor and caches the result. Null results are also
     * cached — a video that failed to resolve once will fail the same way again
     * within the session, and we'd rather not pay the cost a second time.
     *
     * [preferFastStart] selects a 360p muxed MP4 (single AVPlayer, audio in-band)
     * over the higher-quality adaptive split-stream path. Use this for the first
     * hero slide where startup latency matters more than resolution. Results are
     * cached under a separate key so a later quality resolution for the same video
     * is not affected.
     */
    suspend fun resolve(youtubeKey: String, preferFastStart: Boolean = false): TrailerPlaybackSource? {
        // Fast-start and quality paths are cached independently so the first slide's
        // 360p result doesn't clobber a later quality resolution for the same key.
        val cacheKey = if (preferFastStart) "${youtubeKey}@fast" else youtubeKey

        cache[cacheKey]?.let { return it }
        if (cache.containsKey(cacheKey)) return null

        // De-duplicate concurrent in-flight requests for the same key
        val lock = mapLock.withLock {
            inflight.getOrPut(cacheKey) { Mutex() }
        }
        return lock.withLock {
            if (cache.containsKey(cacheKey)) {
                inflight.remove(cacheKey)
                return@withLock cache[cacheKey]
            }
            val source = runCatching {
                TrailerPlaybackResolver.resolveFromYouTubeUrl(
                    "https://www.youtube.com/watch?v=$youtubeKey",
                    preferFastStart = preferFastStart,
                )
            }.getOrNull()
            cache[cacheKey] = source
            inflight.remove(cacheKey)
            source
        }
    }

    /**
     * Returns the quality-path (non-fast-start) cached source for [youtubeKey] if it
     * has already been resolved — without triggering a new extraction. Returns null if
     * not yet cached or if the previous resolution failed.
     *
     * Used by the detail screen to prefer a pre-warmed quality source over the 360p
     * fast-start fallback when one is already available from home screen pre-warming.
     */
    fun getCachedOrNull(youtubeKey: String): TrailerPlaybackSource? = cache[youtubeKey]

    /**
     * Tries each YouTube key in [keys] in order, returning the first one that
     * resolves to a non-null playback source. Use this when a title has multiple
     * candidate trailers (e.g. TMDB's pick plus Stremio's alternates) — if the
     * primary fails (geo-block, age-gate, signature cipher), we fall through to
     * a backup automatically.
     */
    suspend fun resolveFirstAvailable(keys: List<String>, preferFastStart: Boolean = false): TrailerPlaybackSource? {
        for (key in keys) {
            if (key.isBlank()) continue
            val source = resolve(key, preferFastStart = preferFastStart)
            if (source != null) return source
        }
        return null
    }
}
