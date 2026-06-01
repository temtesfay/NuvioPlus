package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform video surface used by the hero carousel to play muted background trailers.
 *
 * - On iOS / Mac Catalyst: wraps AVPlayer in a UIKitView.
 * - On Android: wraps ExoPlayer in an AndroidView.
 * - Implementations should play muted, non-looping, and call [onEnded] when playback finishes.
 *   On error or unsupported platform (App Store build) the composable should emit nothing
 *   so the caller stays on the poster image.
 */
@Composable
expect fun HeroVideoSurface(
    videoUrl: String,
    audioUrl: String?,
    modifier: Modifier,
    isMuted: Boolean,
    onReady: () -> Unit,
    onError: () -> Unit,
    onEnded: () -> Unit,
)
