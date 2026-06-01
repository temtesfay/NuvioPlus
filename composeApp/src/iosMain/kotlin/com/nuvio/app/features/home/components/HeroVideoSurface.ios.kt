package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay

/**
 * iOS / Mac Catalyst: delegates entirely to [HeroPlayerBridge] (AVFoundation in Swift).
 * Falls back to nothing if the factory is not registered (App Store builds where
 * [TrailerPlaybackResolver] already returns null, so this composable is never called).
 */
@OptIn(ExperimentalForeignApi::class)
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
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnEnded = rememberUpdatedState(onEnded)

    // One bridge instance per URL — factory returns null when not registered
    val bridge = remember(videoUrl) {
        HeroPlayerBridgeFactory.create(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            onEnded = { latestOnEnded.value() },
        )
    }

    if (bridge == null) {
        DisposableEffect(videoUrl) {
            latestOnError.value()
            onDispose { }
        }
        return
    }

    // Poll isReady / isFailed every 100 ms. 12 s budget — observed real-world
    // ready times for healthy trailers are 5-7 s (network + decode + layer
    // readiness). A 7 s budget was killing borderline-healthy trailers right as
    // they were about to display. 12 s gives them headroom without making the
    // user wait too long on truly dead streams.
    var didSignalStatus by remember(bridge) { mutableStateOf(false) }
    LaunchedEffect(bridge) {
        val deadlineMs = 12_000L
        var elapsedMs = 0L
        while (!didSignalStatus && elapsedMs < deadlineMs) {
            delay(100)
            elapsedMs += 100
            when {
                bridge.isReady() -> {
                    println("🟢 (HeroTrailer) Bridge ready after ${elapsedMs}ms")
                    didSignalStatus = true
                    latestOnReady.value()
                }
                bridge.isFailed() -> {
                    println("🟡 (HeroTrailer) Bridge failed after ${elapsedMs}ms")
                    didSignalStatus = true
                    latestOnError.value()
                }
            }
        }
        if (!didSignalStatus) {
            // Timed out — caller should advance to the next slide immediately.
            println("🟡 (HeroTrailer) Bridge timed out after ${deadlineMs}ms — skipping slide")
            didSignalStatus = true
            latestOnError.value()
        }
    }

    DisposableEffect(bridge) {
        onDispose { bridge.release() }
    }

    UIKitView(
        modifier = modifier,
        factory = { bridge.createView() },
        update = { bridge.setMuted(isMuted) },
    )
}
