package com.nuvio.app.features.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isMacCatalyst
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val HERO_BACKGROUND_PARALLAX = 0.055f
private const val HERO_BACKGROUND_SCALE = 1.14f
private const val HERO_CONTENT_PARALLAX = 0.18f
private const val HERO_SCROLL_PARALLAX = 0.3f
private const val HERO_SCROLL_DOWN_SCALE_MULTIPLIER = 0.0001f
private const val HERO_SCROLL_UP_SCALE_MULTIPLIER = 0.002f
private const val HERO_SCROLL_MAX_SCALE = 1.3f
private const val HERO_SWIPE_THRESHOLD_FRACTION = 0.16f
private const val HERO_SWIPE_VELOCITY_THRESHOLD = 300f
private const val MOBILE_HERO_VIEWPORT_RATIO = 0.42f
private const val MOBILE_HERO_MIN_HEIGHT_DP = 260f
private const val MOBILE_HERO_MAX_HEIGHT_DP = 360f

internal data class HomeHeroLayout(
    val isTablet: Boolean,
    val heroHeight: Dp,
    val contentMaxWidth: Dp,
    val contentWidthFraction: Float,
    val contentHorizontalPadding: Dp,
    val contentVerticalPadding: Dp,
    val bottomFadeHeight: Dp,
    val logoWidthFraction: Float,
)

@Composable
fun HomeHeroSection(
    items: List<MetaPreview>,
    trailerSources: Map<String, TrailerPlaybackSource> = emptyMap(),
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
    listState: LazyListState? = null,
    onItemClick: ((MetaPreview) -> Unit)? = null,
    onWatchlistClick: ((MetaPreview) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val coroutineScope = rememberCoroutineScope()

    // Trailer source is derived from the stable-scope pre-warmed map passed in from
    // HomeRepository.trailerSources — NO async extraction happens here. This prevents
    // the "coroutine scope left the composition" failure that cancelled all 47+ candidate
    // extractions every time the catalog re-published a state update.
    val settledItem = items.getOrNull(pagerState.settledPage)
    val trailerSource: TrailerPlaybackSource? = settledItem?.let { trailerSources[it.stableKey()] }

    // True once the AVPlayer signals it is buffered and playing.
    // Reset whenever the settled page changes so the poster is shown while buffering.
    var trailerReady by remember { mutableStateOf(false) }
    // Mute state — starts muted (like Prime Video), sticky across slides once toggled.
    var isMuted by remember { mutableStateOf(true) }

    // Reset ready state on slide change
    LaunchedEffect(pagerState.settledPage) {
        trailerReady = false
    }

    // Auto-advance:
    //   • no trailer, enrichment still running (sources map empty) → wait 15 s so
    //     slide 0 doesn't advance before the first YouTube extraction completes (~4-8s)
    //   • no trailer, enrichment done (sources map has entries) → item has no trailer, wait 5 s
    //   • trailer present → primary advance via onEnded below; 90 s safety net
    val trailerSourcesEmpty = trailerSources.isEmpty()
    LaunchedEffect(pagerState.settledPage, trailerSource, trailerSourcesEmpty) {
        if (items.size <= 1) return@LaunchedEffect
        val delayMs = when {
            trailerSource != null -> 90_000L
            trailerSourcesEmpty -> 15_000L
            else -> 5_000L
        }
        delay(delayMs)
        val next = (pagerState.settledPage + 1) % items.size
        pagerState.animateScrollToPage(next)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .homeHeroPagerGesture(
                pagerState = pagerState,
                itemCount = items.size,
                coroutineScope = coroutineScope,
            ),
    ) {
        val layout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )
        val heroWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heroHeightPx = with(LocalDensity.current) { layout.heroHeight.toPx() }
        val scrollOffsetPx by remember(listState, heroHeightPx) {
            derivedStateOf {
                when {
                    listState == null -> 0f
                    listState.firstVisibleItemIndex > 0 -> heroHeightPx
                    else -> listState.firstVisibleItemScrollOffset.toFloat()
                }
            }
        }
        val heroScrollScale = heroBackgroundScrollScale(scrollOffsetPx)
        val heroScrollTranslationY = heroBackgroundScrollTranslationY(scrollOffsetPx)
        val currentPage = pagerState.currentPage.coerceIn(items.indices)
        val visiblePages = listOf(
            currentPage,
            (currentPage - 1).coerceIn(items.indices),
            (currentPage + 1).coerceIn(items.indices),
        ).distinct()
            .mapNotNull { index ->
                val pageOffset = heroPageOffset(pagerState, index)
                val visibility = (1f - abs(pageOffset)).coerceIn(0f, 1f)
                if (visibility <= 0f) {
                    null
                } else {
                    HeroPageLayer(
                        page = index,
                        visibility = visibility,
                        offset = pageOffset,
                    )
                }
            }
            .sortedBy(HeroPageLayer::visibility)
        val currentItem = visiblePages
            .lastOrNull()
            ?.page
            ?.let(items::get)
            ?: items[currentPage]

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight)
                .clip(RectangleShape),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.01f },
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }

            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Poster images — fade out once the trailer is buffered and playing
                val posterAlpha = if (trailerReady) 0f else 1f
                visiblePages.forEach { layer ->
                    AsyncImage(
                        model = items[layer.page].banner ?: items[layer.page].poster,
                        contentDescription = items[layer.page].name,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = layer.visibility * posterAlpha
                                translationX = -layer.offset * heroWidthPx * HERO_BACKGROUND_PARALLAX
                                translationY = heroScrollTranslationY
                                scaleX = HERO_BACKGROUND_SCALE * heroScrollScale
                                scaleY = HERO_BACKGROUND_SCALE * heroScrollScale
                            },
                        alignment = Alignment.TopCenter,
                        contentScale = ContentScale.Crop,
                    )
                }

                // Trailer video — always composed when source is available so AVPlayer can buffer.
                // Visibility is controlled via graphicsLayer alpha: 0.01f while buffering (invisible
                // but composing), 1f once onReady fires. Same alpha-trick used by HorizontalPager above.
                val source = trailerSource
                // key(settledPage) forces HeroVideoSurface to leave and re-enter
                // composition whenever the slide changes. Without this, Compose reuses
                // the same UIKitView node and only calls its `update` block — the
                // UIKit factory is never re-called, so every new bridge's UIView is
                // never added to the hierarchy. The video layer stays invisible
                // (laidOut=false, isReady never fires) and the trailer times out on
                // every slide after the first.
                if (source != null) key(pagerState.settledPage) {
                    // Capture the page this bridge was created for. onError / onEnded
                    // check this before advancing so a bridge that fires just as the
                    // user manually swipes away doesn't read the *new* settledPage and
                    // skip the slide the user actually wanted to land on.
                    val pageWhenStarted = pagerState.settledPage
                    HeroVideoSurface(
                        videoUrl = source.videoUrl,
                        audioUrl = source.audioUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (trailerReady) 1f else 0.01f },
                        isMuted = isMuted,
                        onReady = { trailerReady = true },
                        onError = {
                            trailerReady = false
                            coroutineScope.launch {
                                if (items.size > 1 && pagerState.settledPage == pageWhenStarted) {
                                    pagerState.animateScrollToPage((pageWhenStarted + 1) % items.size)
                                }
                            }
                        },
                        onEnded = {
                            trailerReady = false
                            coroutineScope.launch {
                                if (items.size > 1 && pagerState.settledPage == pageWhenStarted) {
                                    pagerState.animateScrollToPage((pageWhenStarted + 1) % items.size)
                                }
                            }
                        },
                    )
                } // end key(settledPage)

                // Gradient — transparent top, gentle darkening from 50% down
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.00f),
                                    0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.00f),
                                    0.68f to MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
                                    0.85f to MaterialTheme.colorScheme.background.copy(alpha = 0.52f),
                                    1.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                ),
                            ),
                        ),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(layout.bottomFadeHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )

                // Content column — left-aligned
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            horizontal = layout.contentHorizontalPadding,
                            vertical = layout.contentVerticalPadding,
                        ),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(layout.contentWidthFraction)
                            .widthIn(max = layout.contentMaxWidth),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        visiblePages.forEach { layer ->
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                                },
                            ) {
                                HeroContentBlock(
                                    item = items[layer.page],
                                    layout = layout,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HeroActionButtons(
                        item = currentItem,
                        onWatchClick = onItemClick,
                        onWatchlistClick = onWatchlistClick,
                        onInfoClick = onItemClick,
                    )

                    // Page-indicator dots — always centred regardless of content alignment
                    if (items.size > 1) {
                        Spacer(modifier = Modifier.height(if (layout.isTablet) 14.dp else 8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                items.forEachIndexed { index, _ ->
                                    val activeFraction = heroPageVisibility(pagerState, index)
                                    Box(
                                        modifier = Modifier
                                            .clickable {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(index)
                                                }
                                            }
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .graphicsLayer {
                                                alpha = 0.35f + (0.57f * activeFraction)
                                            }
                                            .width(6.dp + (18.dp * activeFraction))
                                            .height(6.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Mute / unmute button — visible in top-right when trailer is playing.
                // statusBarsPadding() keeps it clear of the Dynamic Island / notch.
                if (trailerReady) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(
                                top = if (layout.isTablet) 16.dp else 12.dp,
                                end = if (layout.isTablet) 20.dp else 16.dp,
                            )
                            .size(36.dp)
                            .clickable { isMuted = !isMuted },
                        color = Color.Black.copy(alpha = 0.50f),
                        contentColor = Color.White,
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = if (isMuted) "Unmute trailer" else "Mute trailer",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Side prev / next chevron buttons (tablet / Mac only)
                if (layout.isTablet && items.size > 1) {
                    HeroNavButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage - 1).coerceAtLeast(0)
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp),
                        isNext = false,
                    )
                    HeroNavButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1).coerceAtMost(items.size - 1)
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp),
                        isNext = true,
                    )
                }
            }
        }
    }
}

private data class HeroPageLayer(
    val page: Int,
    val visibility: Float,
    val offset: Float,
)

private fun heroPageOffset(
    pagerState: PagerState,
    page: Int,
): Float = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

private fun heroPageVisibility(
    pagerState: PagerState,
    page: Int,
): Float {
    return (1f - abs(heroPageOffset(pagerState, page))).coerceIn(0f, 1f)
}

@Composable
fun HomeHeroReservedSpace(
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        val layout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight),
        )
    }
}

@Composable
private fun HeroContentBlock(
    item: MetaPreview,
    layout: HomeHeroLayout,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    val titleHoverSource = remember { MutableInteractionSource() }
    val isTitleHovered by titleHoverSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        if (item.logo != null) {
            AsyncImage(
                model = item.logo,
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxWidth(layout.logoWidthFraction)
                    .aspectRatio(2.6f)
                    .hoverable(titleHoverSource)
                    .clickable(enabled = onItemClick != null) {
                        onItemClick?.invoke(item)
                    },
                alignment = Alignment.CenterStart,
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = item.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .hoverable(titleHoverSource)
                    .clickable(enabled = onItemClick != null) {
                        onItemClick?.invoke(item)
                    },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Description shown on hover (desktop / Mac Catalyst)
        AnimatedVisibility(
            visible = isTitleHovered && !item.description.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.description ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroTypeBadge(text = item.type.uppercase())
            item.genres.firstOrNull()?.let { genre ->
                HeroMetaDot()
                HeroMetaText(text = genre)
            }
            item.releaseInfo?.takeIf { it.isNotBlank() }?.let { info ->
                HeroMetaDot()
                HeroMetaText(text = formatReleaseDateForDisplay(info))
            }
            item.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
                HeroMetaDot()
                HeroMetaText(text = "IMDb $rating")
            }
        }
    }
}

@Composable
private fun HeroMetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun homeHeroLayout(
    maxWidthDp: Float,
    viewportHeightDp: Float? = null,
    mobileBelowSectionHeightHintDp: Float? = null,
): HomeHeroLayout =
    when {
        maxWidthDp >= 1200f -> HomeHeroLayout(
            isTablet = true,
            // Mac Catalyst gets a taller hero so Continue Watching starts further down
            // the page, giving the hero more visual presence on the larger screen.
            heroHeight = if (isMacCatalyst) {
                (maxWidthDp * 0.62f).dp.coerceIn(680.dp, 860.dp)
            } else {
                (maxWidthDp * 0.50f).dp.coerceIn(480.dp, 620.dp)
            },
            contentMaxWidth = 640.dp,
            contentWidthFraction = 0.50f,
            contentHorizontalPadding = 56.dp,
            contentVerticalPadding = 28.dp,
            bottomFadeHeight = 260.dp,
            logoWidthFraction = 0.52f,
        )
        maxWidthDp >= 840f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = if (isMacCatalyst) {
                (maxWidthDp * 0.65f).dp.coerceIn(620.dp, 780.dp)
            } else {
                (maxWidthDp * 0.54f).dp.coerceIn(440.dp, 580.dp)
            },
            contentMaxWidth = 560.dp,
            contentWidthFraction = 0.58f,
            contentHorizontalPadding = 40.dp,
            contentVerticalPadding = 24.dp,
            bottomFadeHeight = 240.dp,
            logoWidthFraction = 0.54f,
        )
        maxWidthDp >= 600f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = (maxWidthDp * 0.62f).dp.coerceIn(400.dp, 500.dp),
            contentMaxWidth = 520.dp,
            contentWidthFraction = 0.72f,
            contentHorizontalPadding = 32.dp,
            contentVerticalPadding = 20.dp,
            bottomFadeHeight = 210.dp,
            logoWidthFraction = 0.54f,
        )
        else -> HomeHeroLayout(
            isTablet = false,
            heroHeight = mobileHeroHeight(
                maxWidthDp = maxWidthDp,
                viewportHeightDp = viewportHeightDp,
                mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHintDp,
            ),
            contentMaxWidth = 480.dp,
            contentWidthFraction = 1f,
            contentHorizontalPadding = 16.dp,
            contentVerticalPadding = 10.dp,
            bottomFadeHeight = 120.dp,
            logoWidthFraction = 0.50f,
        )
    }

private fun mobileHeroHeight(
    maxWidthDp: Float,
    viewportHeightDp: Float?,
    mobileBelowSectionHeightHintDp: Float?,
): Dp {
    val viewportDrivenHeight = viewportHeightDp?.let { (it * MOBILE_HERO_VIEWPORT_RATIO).dp }
    val widthFallbackHeight = (maxWidthDp * 0.80f).dp
    val baseHeight = viewportDrivenHeight ?: widthFallbackHeight

    val cappedHeight = if (viewportHeightDp != null && mobileBelowSectionHeightHintDp != null) {
        val maxAllowedFromViewport = (viewportHeightDp - mobileBelowSectionHeightHintDp).dp
        baseHeight.coerceAtMost(maxAllowedFromViewport)
    } else {
        baseHeight
    }

    return cappedHeight.coerceIn(MOBILE_HERO_MIN_HEIGHT_DP.dp, MOBILE_HERO_MAX_HEIGHT_DP.dp)
}

@Composable
private fun HeroMetaDot() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)),
    )
}

@Composable
private fun HeroTypeBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroActionButtons(
    item: MetaPreview,
    onWatchClick: ((MetaPreview) -> Unit)?,
    onWatchlistClick: ((MetaPreview) -> Unit)?,
    onInfoClick: ((MetaPreview) -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.clickable(enabled = onWatchClick != null) { onWatchClick?.invoke(item) },
            color = Color.White,
            contentColor = Color.Black,
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.home_hero_watch_now),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (onWatchlistClick != null) {
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onWatchlistClick(item) },
                color = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(Res.string.home_hero_add_to_watchlist),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (onInfoClick != null) {
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onInfoClick(item) },
                color = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(Res.string.home_hero_more_info),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroNavButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isNext: Boolean,
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isNext) {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                },
                contentDescription = if (isNext) "Next" else "Previous",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun heroBackgroundScrollScale(scrollOffsetPx: Float): Float {
    val scaleIncrease = if (scrollOffsetPx < 0f) {
        abs(scrollOffsetPx) * HERO_SCROLL_UP_SCALE_MULTIPLIER
    } else {
        scrollOffsetPx * HERO_SCROLL_DOWN_SCALE_MULTIPLIER
    }
    return (1f + scaleIncrease).coerceAtMost(HERO_SCROLL_MAX_SCALE)
}

private fun heroBackgroundScrollTranslationY(scrollOffsetPx: Float): Float {
    return scrollOffsetPx * HERO_SCROLL_PARALLAX
}

private fun Modifier.homeHeroPagerGesture(
    pagerState: PagerState,
    itemCount: Int,
    coroutineScope: CoroutineScope,
): Modifier {
    if (itemCount <= 1) return this

    return pointerInput(pagerState, itemCount) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val widthPx = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply {
                addPosition(down.uptimeMillis, down.position)
            }
            val startPage = pagerState.currentPage
            var totalDx = 0f
            var totalDy = 0f
            var dragging = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (!change.pressed) {
                    if (dragging) {
                        val targetPage = resolveHeroTargetPage(
                            startPage = startPage,
                            itemCount = itemCount,
                            totalDx = totalDx,
                            velocityX = velocityTracker.calculateVelocity().x,
                            widthPx = widthPx,
                        )
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                    break
                }

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!dragging) {
                    val horizontalDrag =
                        abs(totalDx) > viewConfiguration.touchSlop && abs(totalDx) > abs(totalDy)
                    val verticalDrag =
                        abs(totalDy) > viewConfiguration.touchSlop && abs(totalDy) > abs(totalDx)

                    when {
                        verticalDrag -> break
                        horizontalDrag -> dragging = true
                        else -> continue
                    }
                }

                pagerState.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}

private fun resolveHeroTargetPage(
    startPage: Int,
    itemCount: Int,
    totalDx: Float,
    velocityX: Float,
    widthPx: Float,
): Int {
    val thresholdPassed = abs(totalDx) > widthPx * HERO_SWIPE_THRESHOLD_FRACTION ||
        abs(velocityX) > HERO_SWIPE_VELOCITY_THRESHOLD
    if (!thresholdPassed) return startPage

    return when {
        totalDx > 0f -> (startPage - 1).coerceAtLeast(0)
        totalDx < 0f -> (startPage + 1).coerceAtMost(itemCount - 1)
        else -> startPage
    }
}
