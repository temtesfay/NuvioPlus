package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.isMacCatalyst
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIScreen
import platform.UIKit.UISlider

private const val lockPlayerToLandscapeNotification = "NuvioPlayerLockLandscape"
private const val unlockPlayerOrientationNotification = "NuvioPlayerUnlockOrientation"

@Composable
actual fun LockPlayerToLandscape() {
    DisposableEffect(Unit) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            lockPlayerToLandscapeNotification,
            null,
        )

        onDispose {
            NSNotificationCenter.defaultCenter.postNotificationName(
                unlockPlayerOrientationNotification,
                null,
            )
        }
    }
}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.setIdleTimerDisabled(keepScreenAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            UIApplication.sharedApplication.setIdleTimerDisabled(false)
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
) = Unit

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? {
    val controller = remember { IOSPlayerGestureController() }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

@Composable
actual fun SyncMacCursorWithControls(controlsVisible: Boolean) {
    if (!isMacCatalyst) return
    // When Kotlin hides the controls, tell Swift to hide the cursor at the same instant.
    // Mouse movement (handled in Swift) will show both the cursor and controls again.
    androidx.compose.runtime.LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                aName = "NuvioPlayerHideCursor",
                `object` = null,
            )
        }
    }
}

@Composable
actual fun WatchForPlayerKeyboardShortcuts(
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
) {
    val onPlayPauseState = rememberUpdatedState(onPlayPause)
    val onSeekBackwardState = rememberUpdatedState(onSeekBackward)
    val onSeekForwardState = rememberUpdatedState(onSeekForward)
    DisposableEffect(Unit) {
        val playPauseObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = "NuvioPlayerKeyPlayPause",
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onPlayPauseState.value() }
        val seekBackwardObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = "NuvioPlayerKeySeekBackward",
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onSeekBackwardState.value() }
        val seekForwardObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = "NuvioPlayerKeySeekForward",
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onSeekForwardState.value() }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(playPauseObserver)
            NSNotificationCenter.defaultCenter.removeObserver(seekBackwardObserver)
            NSNotificationCenter.defaultCenter.removeObserver(seekForwardObserver)
        }
    }
}

@Composable
actual fun WatchForMacCursorShowControls(onShow: () -> Unit) {
    // UIDevice.userInterfaceIdiom returns .pad (not .mac) on "Designed for iPad"
    // Mac Catalyst builds, so that check is wrong here. isMacCatalyst (backed by
    // NSProcessInfo.isMacCatalystApp) is the correct guard for all configurations.
    if (!isMacCatalyst) return
    val onShowState = rememberUpdatedState(onShow)
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = "NuvioPlayerShowControls",
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onShowState.value() }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
}

private class IOSPlayerGestureController : PlayerGestureController {
    private val volumeView = MPVolumeView().apply {
        hidden = true
        alpha = 0.01
    }
    private val originalBrightness = UIScreen.mainScreen.brightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float =
        UIScreen.mainScreen.brightness.toFloat().coerceIn(0.02f, 1f)

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0.02f, 1f)
        UIScreen.mainScreen.brightness = target.toDouble()
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val current = (volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()?.value ?: 0f)
            .coerceIn(0f, 1f)
        return PlayerAudioLevel(
            fraction = current,
            isMuted = current <= 0.001f,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val target = level.coerceIn(0f, 1f)
        val slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
            ?: return currentVolume()
        slider.value = target
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        return PlayerAudioLevel(
            fraction = target,
            isMuted = target <= 0.001f,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true
        UIScreen.mainScreen.brightness = originalBrightness
    }
}
