package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier

/**
 * Android stub — trailer playback in the hero is not yet implemented on Android.
 * Signals onError immediately so the caller stays on the poster image.
 */
@Composable
actual fun HeroVideoSurface(
    videoUrl: String,
    audioUrl: String?,
    modifier: Modifier,
    isMuted: Boolean,
    onReady: () -> Unit,
    onError: () -> Unit,
    onEnded: () -> Unit,
) {
    DisposableEffect(videoUrl) {
        onError()
        onDispose { }
    }
}
