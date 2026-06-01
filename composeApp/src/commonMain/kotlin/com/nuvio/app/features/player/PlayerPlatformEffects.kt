package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

interface PlayerGestureController {
    fun currentBrightness(): Float?
    fun setBrightness(level: Float): Float?
    fun currentVolume(): PlayerAudioLevel?
    fun setVolume(level: Float): PlayerAudioLevel?
}

data class PlayerAudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

@Composable
expect fun LockPlayerToLandscape()

@Composable
expect fun EnterImmersivePlayerMode(keepScreenAwake: Boolean)

@Composable
expect fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
)

@Composable
expect fun rememberPlayerGestureController(): PlayerGestureController?

@Composable
expect fun WatchForMacCursorShowControls(onShow: () -> Unit)

@Composable
expect fun WatchForPlayerKeyboardShortcuts(
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
)

/** Posts a platform signal to hide the cursor when [controlsVisible] becomes false. */
@Composable
expect fun SyncMacCursorWithControls(controlsVisible: Boolean)
