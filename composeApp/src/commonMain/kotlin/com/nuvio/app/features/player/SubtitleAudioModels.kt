package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_track_number
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

data class AudioTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
)

data class SubtitleTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isForced: Boolean = false,
)

data class AddonSubtitle(
    val id: String,
    val url: String,
    val language: String,
    val display: String,
    val isSelected: Boolean = false,
)

enum class SubtitleTab {
    BuiltIn,
    Addons,
    Style,
}

enum class SubtitleFontFamily(val displayName: String) {
    Auto("Auto"),
    SansSerif("Sans"),
    Serif("Serif"),
    Monospace("Mono"),
}

enum class SubtitleFontWeight(val displayName: String) {
    Thin("Thin"),
    Light("Light"),
    Regular("Regular"),
    Bold("Bold"),
    Heavy("Heavy"),
}

data class SubtitleStyleState(
    val textColor: Color = Color.White,
    val outlineEnabled: Boolean = false,
    val fontSizeSp: Int = 18,
    val bottomOffset: Int = 20,
    val backgroundColor: Color = Color.Black,
    val backgroundOpacity: Float = 0f,
    val fontFamily: SubtitleFontFamily = SubtitleFontFamily.Auto,
    val fontWeight: SubtitleFontWeight = SubtitleFontWeight.Regular,
    val useSystemSubtitleSettings: Boolean = false,
) {
    companion object {
        val DEFAULT = SubtitleStyleState()
    }
}

/**
 * Maps a (family, weight) pair to an actual iOS PostScript font name that
 * libmpv/CoreText can resolve. Returns null for the user's "Auto" choice at
 * Regular weight (uses MPV's default), so we don't override system default
 * unnecessarily.
 *
 * Notes:
 *  - Georgia has no Light/Thin variant; falls back to Regular.
 *  - Menlo (the iOS monospace font) only has Regular/Bold/Italic; lighter
 *    weights fall back to Regular.
 *  - HelveticaNeue is the most complete: Thin / UltraLight / Light /
 *    Regular / Medium / Bold / CondensedBlack.
 */
fun SubtitleStyleState.toMpvFontName(): String? = when (fontFamily) {
    SubtitleFontFamily.Auto -> if (fontWeight == SubtitleFontWeight.Regular) {
        null
    } else {
        helveticaNeueForWeight(fontWeight)
    }
    SubtitleFontFamily.SansSerif -> helveticaNeueForWeight(fontWeight)
    SubtitleFontFamily.Serif -> georgiaForWeight(fontWeight)
    SubtitleFontFamily.Monospace -> menloForWeight(fontWeight)
}

private fun helveticaNeueForWeight(weight: SubtitleFontWeight): String = when (weight) {
    SubtitleFontWeight.Thin -> "HelveticaNeue-Thin"
    SubtitleFontWeight.Light -> "HelveticaNeue-Light"
    SubtitleFontWeight.Regular -> "HelveticaNeue"
    SubtitleFontWeight.Bold -> "HelveticaNeue-Bold"
    SubtitleFontWeight.Heavy -> "HelveticaNeue-CondensedBlack"
}

private fun georgiaForWeight(weight: SubtitleFontWeight): String = when (weight) {
    SubtitleFontWeight.Bold, SubtitleFontWeight.Heavy -> "Georgia-Bold"
    else -> "Georgia"
}

private fun menloForWeight(weight: SubtitleFontWeight): String = when (weight) {
    SubtitleFontWeight.Bold, SubtitleFontWeight.Heavy -> "Menlo-Bold"
    else -> "Menlo-Regular"
}

/**
 * Weight is now encoded directly into the PostScript font name, so we never
 * need to ask MPV to synthesize a bold (which can produce smeary results in
 * strip-renderer mode). Always returns false.
 */
fun SubtitleStyleState.toMpvIsBold(): Boolean = false

val SubtitleColorSwatches = listOf(
    Color.White,
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88),
    Color(0xFF9B59B6),
    Color(0xFFF97316),
    Color(0xFF22C55E),
    Color(0xFF3B82F6),
    Color.Black,
)

fun Color.toStorageHexString(): String {
    fun component(value: Float): String =
        (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

    return buildString {
        append('#')
        append(component(alpha))
        append(component(red))
        append(component(green))
        append(component(blue))
    }
}

fun subtitleColorFromStorage(value: String?): Color? {
    val normalized = value
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 || it.length == 8 }
        ?: return null

    val argb = if (normalized.length == 6) {
        "FF$normalized"
    } else {
        normalized
    }

    val parsed = argb.toLongOrNull(16) ?: return null
    return Color(
        red = ((parsed shr 16) and 0xFF).toFloat() / 255f,
        green = ((parsed shr 8) and 0xFF).toFloat() / 255f,
        blue = (parsed and 0xFF).toFloat() / 255f,
        alpha = ((parsed shr 24) and 0xFF).toFloat() / 255f,
    )
}

data class SubtitleAudioUiState(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val addonSubtitles: List<AddonSubtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val addonSubtitleError: String? = null,
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val selectedAddonSubtitleId: String? = null,
    val useCustomSubtitles: Boolean = false,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val showAudioModal: Boolean = false,
    val showSubtitleModal: Boolean = false,
    val activeSubtitleTab: SubtitleTab = SubtitleTab.BuiltIn,
)

@Composable
fun localizedTrackDisplayName(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank()) return languageLabelForCode(language)
    return stringResource(Res.string.compose_player_track_number, index + 1)
}
