package com.nuvio.app.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isMacCatalyst

internal const val PosterLandscapeAspectRatio = 1.77f
private const val PosterLandscapeWidthScale = 180f / 110f

internal fun landscapePosterWidth(basePosterWidthDp: Int): Dp =
    (basePosterWidthDp * PosterLandscapeWidthScale * if (isMacCatalyst) 1.5f else 1.0f).dp

internal fun landscapePosterHeightForWidth(width: Dp): Dp =
    (width.value / PosterLandscapeAspectRatio).dp
