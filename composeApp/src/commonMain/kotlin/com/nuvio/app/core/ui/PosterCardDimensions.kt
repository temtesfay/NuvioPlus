package com.nuvio.app.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isMacCatalyst

internal const val PosterLandscapeAspectRatio = 1.77f
private const val PosterLandscapeWidthScale = 180f / 110f
private const val MacCatalystPosterScale = 1.5f

internal fun landscapePosterWidth(basePosterWidthDp: Int): Dp =
    (basePosterWidthDp * PosterLandscapeWidthScale * if (isMacCatalyst) MacCatalystPosterScale else 1.0f).dp

internal fun landscapePosterHeightForWidth(width: Dp): Dp =
    (width.value / PosterLandscapeAspectRatio).dp

/// Portrait (vertical) and square poster width — scaled ×1.5 on Mac Catalyst
/// to match the larger screen, consistent with landscape poster scaling.
internal fun portraitPosterWidth(basePosterWidthDp: Int): Dp =
    (basePosterWidthDp * if (isMacCatalyst) MacCatalystPosterScale else 1.0f).dp
