package com.nuvio.app.features.player.skip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.isMacCatalyst
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_episode_title_format
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_next_episode
import nuvio.composeapp.generated.resources.player_next_episode_finding_source
import nuvio.composeapp.generated.resources.player_next_episode_playing_via_countdown
import nuvio.composeapp.generated.resources.player_next_episode_thumbnail
import nuvio.composeapp.generated.resources.player_next_episode_unaired
import org.jetbrains.compose.resources.stringResource

@Composable
fun NextEpisodeCard(
    nextEpisode: NextEpisodeInfo?,
    visible: Boolean,
    isAutoPlaySearching: Boolean,
    autoPlaySourceName: String?,
    autoPlayCountdownSec: Int?,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nextEpisode == null) return

    val isPlayable = nextEpisode.hasAired

    // Mac Catalyst runs on a large desktop screen; scale the phone-sized card up.
    val mac = isMacCatalyst
    val cardMaxWidth = if (mac) 400.dp else 292.dp
    val cardCorner = if (mac) 20.dp else 16.dp
    val cardPaddingH = if (mac) 12.dp else 9.dp
    val cardPaddingV = if (mac) 11.dp else 8.dp
    val thumbWidth = if (mac) 108.dp else 78.dp
    val thumbHeight = if (mac) 61.dp else 44.dp
    val thumbCorner = if (mac) 12.dp else 9.dp
    val infoSpacing = if (mac) 11.dp else 8.dp
    val labelSize = if (mac) 13.sp else 10.sp
    val titleSize = if (mac) 16.sp else 12.sp
    val statusSize = if (mac) 13.sp else 10.sp
    val playIconSize = if (mac) 18.dp else 13.dp
    val playLabelSize = if (mac) 15.sp else 11.sp

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(cardCorner)
        Row(
            modifier = Modifier
                .widthIn(max = cardMaxWidth)
                .clip(shape)
                .background(Color(0xFF191919).copy(alpha = 0.89f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .clickable { if (isPlayable) onPlayNext() }
                .padding(horizontal = cardPaddingH, vertical = cardPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(width = thumbWidth, height = thumbHeight)
                    .clip(RoundedCornerShape(thumbCorner)),
            ) {
                AsyncImage(
                    model = nextEpisode.thumbnail,
                    contentDescription = stringResource(Res.string.player_next_episode_thumbnail),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.32f),
                                ),
                            ),
                        ),
                )
            }

            Spacer(modifier = Modifier.width(infoSpacing))

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Res.string.player_next_episode),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        Res.string.compose_player_episode_title_format,
                        nextEpisode.season,
                        nextEpisode.episode,
                        nextEpisode.title,
                    ),
                    color = Color.White,
                    fontSize = titleSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                val autoPlayStatus = when {
                    !isPlayable && !nextEpisode.unairedMessage.isNullOrBlank() -> nextEpisode.unairedMessage
                    isAutoPlaySearching -> stringResource(Res.string.player_next_episode_finding_source)
                    !autoPlaySourceName.isNullOrBlank() && autoPlayCountdownSec != null ->
                        stringResource(
                            Res.string.player_next_episode_playing_via_countdown,
                            autoPlaySourceName,
                            autoPlayCountdownSec,
                        )
                    else -> null
                }
                if (autoPlayStatus != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = autoPlayStatus,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = statusSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Play badge
            Row(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isPlayable) Color.White else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(playIconSize),
                )
                Text(
                    text = if (isPlayable) {
                        stringResource(Res.string.detail_btn_play)
                    } else {
                        stringResource(Res.string.player_next_episode_unaired)
                    },
                    color = if (isPlayable) Color.White else Color.White.copy(alpha = 0.72f),
                    fontSize = playLabelSize,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}
