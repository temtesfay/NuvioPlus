package com.nuvio.app.features.details.components

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
import com.nuvio.app.features.home.components.HeroPlayerBridgeFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnEnded = rememberUpdatedState(onEnded)

    val bridge = remember(sourceUrl) {
        HeroPlayerBridgeFactory.create(
            videoUrl = sourceUrl,
            audioUrl = sourceAudioUrl,
            onEnded = { latestOnEnded.value() },
        )
    }

    if (bridge == null) {
        DisposableEffect(sourceUrl) {
            latestOnError.value()
            onDispose { }
        }
        return
    }

    var didSignalStatus by remember(bridge) { mutableStateOf(false) }
    LaunchedEffect(bridge) {
        val deadlineMs = 12_000L
        var elapsedMs = 0L
        while (!didSignalStatus && elapsedMs < deadlineMs) {
            delay(100)
            elapsedMs += 100
            when {
                bridge.isReady() -> {
                    didSignalStatus = true
                    latestOnReady.value()
                }
                bridge.isFailed() -> {
                    didSignalStatus = true
                    latestOnError.value()
                }
            }
        }
        if (!didSignalStatus) {
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
        update = { bridge.setMuted(muted) },
    )
}
