package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String, preferFastStart: Boolean): TrailerPlaybackSource? = null
}