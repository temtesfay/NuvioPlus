package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String, preferFastStart: Boolean): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        return extractor.extractPlaybackSource(youtubeUrl, preferFastStart = preferFastStart)
    }
}