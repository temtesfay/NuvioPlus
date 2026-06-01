package com.nuvio.app.features.home.components

import platform.UIKit.UIView

/**
 * Bridge interface implemented by the Swift side (NuvioHeroPlayerBridge.swift).
 * One instance is created per hero slide that has a resolved trailer URL.
 */
interface HeroPlayerBridge {
    /** The UIView containing the AVPlayerLayer. */
    fun createView(): UIView

    /** Release all AVFoundation resources. Call when the composable leaves the composition. */
    fun release()

    /** True once AVPlayerItem.status == ReadyToPlay. Poll until true or [isFailed]. */
    fun isReady(): Boolean

    /** True when the player encountered an error. */
    fun isFailed(): Boolean

    /** Mute or unmute the player. Starts muted by default. */
    fun setMuted(muted: Boolean)
}

/**
 * Swift implements this and registers it before Compose initialises.
 * The factory receives the resolved playback URL so Swift can build AVPlayerItem eagerly.
 */
interface HeroPlayerBridgeCreator {
    fun create(
        videoUrl: String,
        audioUrl: String?,
        onEnded: () -> Unit,
    ): HeroPlayerBridge
}

/**
 * Singleton factory — mirrors the NuvioPlayerBridgeFactory pattern.
 * Swift calls [registerFactory] in NuvioHeroPlayerRegistration.register().
 */
object HeroPlayerBridgeFactory {
    private var creator: HeroPlayerBridgeCreator? = null

    fun registerFactory(creator: HeroPlayerBridgeCreator) {
        this.creator = creator
    }

    fun create(
        videoUrl: String,
        audioUrl: String?,
        onEnded: () -> Unit,
    ): HeroPlayerBridge? = creator?.create(
        videoUrl = videoUrl,
        audioUrl = audioUrl,
        onEnded = onEnded,
    )
}
