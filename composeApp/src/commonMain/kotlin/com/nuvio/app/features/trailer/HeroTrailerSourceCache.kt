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
     */
    suspend fun resolve(youtubeKey: String): TrailerPlaybackSource? {
        // Fast path — already cached
        cache[youtubeKey]?.let { return it }
        if (cache.containsKey(youtubeKey)) return null

        // De-duplicate concurrent in-flight requests for the same key
        val lock = mapLock.withLock {
            inflight.getOrPut(youtubeKey) { Mutex() }
        }
        return lock.withLock {
            // Re-check inside the lock — another caller may have populated the cache
            // while we were waiting.
            if (cache.containsKey(youtubeKey)) {
                inflight.remove(youtubeKey)
                return@withLock cache[youtubeKey]
            }
            val source = runCatching {
                TrailerPlaybackResolver.resolveFromYouTubeUrl(
                    "https://www.youtube.com/watch?v=$youtubeKey"
                )
            }.getOrNull()
            cache[youtubeKey] = source
            inflight.remove(youtubeKey)
            source
        }
    }

    /**
     * Tries each YouTube key in [keys] in order, returning the first one that
     * resolves to a non-null playback source. Use this when a title has multiple
     * candidate trailers (e.g. TMDB's pick plus Stremio's alternates) — if the
     * primary fails (geo-block, age-gate, signature cipher), we fall through to
     * a backup automatically.
     */
    suspend fun resolveFirstAvailable(keys: List<String>): TrailerPlaybackSource? {
        for (key in keys) {
            if (key.isBlank()) continue
            val source = resolve(key)
            if (source != null) return source
        }
        return null
    }
}
