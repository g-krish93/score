package uk.co.cricrelay.mobile.feature.studio

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppMotion
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.LabeledSlider
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.SelectableOptionCard
import uk.co.cricrelay.mobile.ui.SheetHeader
import uk.co.cricrelay.mobile.ui.StudioTextField
import uk.co.cricrelay.shared.model.BoardPreset
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.SponsorDisplayMode
import uk.co.cricrelay.shared.model.SponsorLayoutMode
import uk.co.cricrelay.shared.model.StabilizationLevel

@Composable
fun DestinationSheet(
    state: StudioUiState,
    onSaveCustom: (String, String, String) -> Unit,
    onSaveAsDestination: (String, String, String, String) -> Unit,
    onSelectSaved: (String) -> Unit,
    onSelect: (StreamDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var rtmpUrl by remember(state.customRtmpUrl) { mutableStateOf(state.customRtmpUrl) }
    var streamKey by remember(state.customStreamKey) { mutableStateOf(state.customStreamKey) }
    var watchUrl by remember(state.customWatchUrl) { mutableStateOf(state.customWatchUrl) }
    var saveLabel by remember { mutableStateOf("") }

    SheetHeader(
        title = "Stream destination",
        subtitle = "Reuse saved keys for each XI, or paste once. Clubs can also use OAuth.",
    )
    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        if (state.savedDestinations.isNotEmpty()) {
            Text(
                text = "Saved destinations",
                style = AppTypography.bodySmall,
                color = AppColors.OnBackgroundMuted,
                modifier = Modifier.padding(bottom = AppSpacing.xs),
            )
            state.savedDestinations.forEach { dest ->
                SelectableOptionCard(
                    title = dest.label.ifBlank { "RTMP" },
                    description = dest.streamKeyMasked.ifBlank { dest.rtmpUrl },
                    icon = Icons.Outlined.VpnKey,
                    iconTint = AppColors.Accent,
                    selected = state.destination == StreamDestination.Custom &&
                        state.selectedSavedDestinationId == dest.id,
                    onClick = {
                        onSelectSaved(dest.id)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(AppSpacing.sm))
        }
        SelectableOptionCard(
            title = "YouTube",
            description = "Club account via OAuth",
            icon = Icons.Outlined.SmartDisplay,
            iconTint = AppColors.YouTube,
            selected = state.destination == StreamDestination.YouTube,
            onClick = { onSelect(StreamDestination.YouTube) },
        )
        SelectableOptionCard(
            title = "Twitch",
            description = "Club account via OAuth",
            icon = Icons.Outlined.SportsEsports,
            iconTint = AppColors.Twitch,
            selected = state.destination == StreamDestination.Twitch,
            onClick = { onSelect(StreamDestination.Twitch) },
        )
        SelectableOptionCard(
            title = "One-off Custom RTMP",
            description = "Paste any server URL and stream key",
            icon = Icons.Outlined.VpnKey,
            iconTint = AppColors.Accent,
            selected = state.destination == StreamDestination.Custom &&
                state.selectedSavedDestinationId == null,
            onClick = { onSelect(StreamDestination.Custom) },
        )
    }
    AnimatedVisibility(
        visible = state.destination == StreamDestination.Custom &&
            state.selectedSavedDestinationId == null,
        enter = expandVertically(
            animationSpec = tween(AppMotion.SheetEnterMs, easing = AppMotion.EaseOut),
        ) + fadeIn(AppMotion.enterSpec(AppMotion.SheetEnterMs)),
        exit = shrinkVertically(
            animationSpec = tween(AppMotion.SheetExitMs, easing = AppMotion.EaseOut),
        ) + fadeOut(AppMotion.exitSpec(AppMotion.SheetExitMs)),
    ) {
        Column {
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = rtmpUrl,
                onValueChange = { rtmpUrl = it },
                label = "RTMP server URL",
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
            StudioTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                label = "Stream key",
                isPassword = true,
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
            StudioTextField(
                value = watchUrl,
                onValueChange = { watchUrl = it },
                label = "Watch URL (optional)",
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
            StudioTextField(
                value = saveLabel,
                onValueChange = { saveLabel = it },
                label = "Save as (optional label)",
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.md))
            PrimaryButton(
                text = "Use for this match",
                onClick = {
                    onSaveCustom(rtmpUrl, streamKey, watchUrl)
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
            SecondaryButton(
                text = "Save to club vault",
                onClick = {
                    onSaveAsDestination(saveLabel, rtmpUrl, streamKey, watchUrl)
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
        }
    }
    AnimatedVisibility(
        visible = state.destination != StreamDestination.Custom,
        enter = fadeIn(AppMotion.enterSpec(AppMotion.SheetEnterMs)),
        exit = fadeOut(AppMotion.exitSpec(AppMotion.SheetExitMs)),
    ) {
        Column {
            Spacer(Modifier.height(AppSpacing.md))
            PrimaryButton(
                text = "Use ${state.destination.label}",
                onClick = {
                    onSelect(state.destination)
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
        }
    }
}

/**
 * Parse a [BoardPreset] swatch value — either `#RRGGBB` hex or the CSS `rgba(r,g,b,a)`
 * strings the overlay page paints with — into a Compose [Color] for the preset chips.
 */
private fun parseSwatchColor(value: String): Color {
    val v = value.trim()
    if (v.startsWith("#")) {
        val hex = v.removePrefix("#")
        val rgb = hex.toLongOrNull(16) ?: return Color.White
        return if (hex.length == 6) Color(0xFF000000L or rgb) else Color(rgb)
    }
    if (v.startsWith("rgba(") || v.startsWith("rgb(")) {
        val parts = v.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        val r = parts.getOrNull(0)?.toIntOrNull() ?: return Color.White
        val g = parts.getOrNull(1)?.toIntOrNull() ?: return Color.White
        val b = parts.getOrNull(2)?.toIntOrNull() ?: return Color.White
        val a = ((parts.getOrNull(3)?.toFloatOrNull() ?: 1f) * 255).toInt().coerceIn(0, 255)
        return Color(r, g, b, a)
    }
    return Color.White
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlaySheet(
    prefs: OverlayLayoutPrefs,
    sponsors: List<Sponsor>,
    onPreview: (OverlayLayoutPrefs) -> Unit,
    onSave: (OverlayLayoutPrefs) -> Unit,
    onArrange: () -> Unit,
    onDismiss: () -> Unit,
) {
    var fontScale by remember { mutableStateOf(prefs.fontScale.toFloat()) }
    var widthFraction by remember { mutableStateOf(prefs.clampedWidthFraction().toFloat()) }
    var heightFraction by remember { mutableStateOf(prefs.clampedHeightFraction().toFloat()) }
    var opacity by remember { mutableStateOf(prefs.opacity.toFloat()) }
    var bottomMargin by remember { mutableStateOf(prefs.bottomMargin.toFloat()) }
    var bg by remember { mutableStateOf(prefs.bgColor) }
    var text by remember { mutableStateOf(prefs.textColor) }
    var themeId by remember { mutableStateOf(OverlayLayoutPrefs.sanitizeTheme(prefs.theme)) }
    var bowlingIsland by remember { mutableStateOf(prefs.bowlingIslandEnabled) }
    var watermarkEnabled by remember { mutableStateOf(prefs.watermarkEnabled) }
    var watermarkText by remember { mutableStateOf(prefs.watermarkText) }
    var sponsorEnabled by remember { mutableStateOf(prefs.sponsorEnabled) }
    var activeSponsorIds by remember {
        mutableStateOf(
            prefs.activeSponsorIds.ifEmpty {
                prefs.activeSponsorId?.let { listOf(it) } ?: emptyList()
            },
        )
    }
    var sponsorLayoutMode by remember { mutableStateOf(prefs.sponsorLayoutMode) }
    var sponsorCarouselIntervalSec by remember { mutableStateOf(prefs.sponsorCarouselIntervalSec.toFloat()) }
    var sponsorDisplayMode by remember { mutableStateOf(prefs.sponsorDisplayMode) }
    var sponsorPositionX by remember { mutableStateOf(prefs.sponsorPositionX.toFloat()) }
    var sponsorPositionY by remember { mutableStateOf(prefs.sponsorPositionY.toFloat()) }
    var sponsorSizeScale by remember { mutableStateOf(prefs.sponsorSizeScale.toFloat()) }
    var sponsorOpacity by remember { mutableStateOf(prefs.sponsorOpacity.toFloat()) }
    var sponsorScrollSpeed by remember { mutableStateOf(prefs.sponsorScrollSpeed.toFloat()) }
    var sponsorScrollDirection by remember { mutableStateOf(prefs.sponsorScrollDirection) }
    val sponsorScrollMode = SponsorDisplayMode.isScroll(sponsorDisplayMode)

    fun buildDraftPrefs(): OverlayLayoutPrefs = prefs.copy(
        widthFraction = widthFraction.toDouble(),
        heightFraction = heightFraction.toDouble(),
        fontScale = fontScale.toDouble(),
        opacity = opacity.toDouble(),
        bottomMargin = bottomMargin.toDouble(),
        bgColor = bg,
        textColor = text,
        theme = themeId,
        bowlingIslandEnabled = bowlingIsland,
        watermarkEnabled = watermarkEnabled,
        watermarkText = watermarkText.trim().ifBlank { OverlayLayoutPrefs.WATERMARK_DEFAULT_TEXT },
        sponsorEnabled = sponsorEnabled,
        activeSponsorIds = if (sponsorEnabled) activeSponsorIds.take(6) else emptyList(),
        activeSponsorId = activeSponsorIds.firstOrNull(),
        sponsorLayoutMode = sponsorLayoutMode,
        sponsorCarouselIntervalSec = sponsorCarouselIntervalSec.toDouble(),
        sponsorDisplayMode = sponsorDisplayMode,
        sponsorPositionX = sponsorPositionX.toDouble(),
        sponsorPositionY = sponsorPositionY.toDouble(),
        sponsorSizeScale = sponsorSizeScale.toDouble(),
        sponsorOpacity = sponsorOpacity.toDouble(),
        sponsorScrollSpeed = sponsorScrollSpeed.toDouble(),
        sponsorScrollDirection = sponsorScrollDirection,
    )

    LaunchedEffect(
        fontScale,
        widthFraction,
        heightFraction,
        opacity,
        bottomMargin,
        themeId,
        bowlingIsland,
        bg,
        text,
        watermarkEnabled,
        watermarkText,
        sponsorEnabled,
        activeSponsorIds,
        sponsorLayoutMode,
        sponsorCarouselIntervalSec,
        sponsorDisplayMode,
        sponsorPositionX,
        sponsorPositionY,
        sponsorSizeScale,
        sponsorOpacity,
        sponsorScrollSpeed,
        sponsorScrollDirection,
    ) {
        delay(80)
        onPreview(buildDraftPrefs())
    }

    SheetHeader(
        title = "Board Edit",
        subtitle = "Scoreboard style, sponsor logos, and watermark. Changes preview live — tap Save board when done.",
    )

    Box(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Text(
            "◇  Arrange on screen — pinch & drag",
            style = AppTypography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AppColors.Accent,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.radiusSm))
                .background(AppColors.Accent.copy(alpha = 0.12f))
                .border(1.dp, AppColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(AppSpacing.radiusSm))
                .clickable { onArrange() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(AppSpacing.md))

    val activeSponsors = sponsors.filter { it.isActive }
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sponsor logo", style = AppTypography.titleSmall)
                Text(
                    "Club sponsor on the broadcast — fixed or scrolling",
                    style = AppTypography.bodySmall,
                )
            }
            Switch(
                checked = sponsorEnabled,
                onCheckedChange = { sponsorEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.OnBackgroundDim,
                    uncheckedTrackColor = AppColors.SurfaceElevated,
                ),
            )
        }
        when {
            activeSponsors.isEmpty() -> {
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "No sponsors yet — upload logos on the web dashboard under Sponsor logos, then return here.",
                    style = AppTypography.bodySmall,
                    color = AppColors.OnBackgroundDim,
                )
            }
            !sponsorEnabled -> {
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "${activeSponsors.size} sponsor(s) available — turn on to pick one for this stream",
                    style = AppTypography.bodySmall,
                    color = AppColors.Accent,
                )
            }
        }
        if (sponsorEnabled && activeSponsors.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text("How to show", style = AppTypography.bodySmall)
            Spacer(Modifier.height(AppSpacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                listOf(
                    SponsorLayoutMode.SINGLE to "One logo",
                    SponsorLayoutMode.MULTI to "All at once",
                    SponsorLayoutMode.CAROUSEL to "Carousel",
                ).forEach { (id, label) ->
                    val selected = sponsorLayoutMode == id
                    Text(
                        label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.radiusSm))
                            .background(
                                if (selected) AppColors.Primary.copy(alpha = 0.25f)
                                else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) AppColors.Primary else AppColors.Border,
                                shape = RoundedCornerShape(AppSpacing.radiusSm),
                            )
                            .clickable {
                                sponsorLayoutMode = id
                                if (!SponsorLayoutMode.allowsMultiSelect(id) && activeSponsorIds.size > 1) {
                                    activeSponsorIds = activeSponsorIds.take(1)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = AppTypography.bodySmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                when (sponsorLayoutMode) {
                    SponsorLayoutMode.MULTI -> "Select sponsors to show together (up to 6)"
                    SponsorLayoutMode.CAROUSEL -> "Select sponsors to rotate through"
                    else -> "Select sponsor for this match"
                },
                style = AppTypography.bodySmall,
            )
            Spacer(Modifier.height(AppSpacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                activeSponsors.forEach { sponsor ->
                    val multiPick = SponsorLayoutMode.allowsMultiSelect(sponsorLayoutMode)
                    val selected = if (multiPick) {
                        sponsor.id in activeSponsorIds
                    } else {
                        sponsor.id in activeSponsorIds ||
                            (activeSponsorIds.isEmpty() && sponsor.id == activeSponsors.firstOrNull()?.id)
                    }
                    Text(
                        sponsor.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.radiusSm))
                            .background(
                                if (selected) AppColors.Accent.copy(alpha = 0.2f)
                                else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) AppColors.Accent else AppColors.Border,
                                shape = RoundedCornerShape(AppSpacing.radiusSm),
                            )
                            .clickable {
                                if (multiPick) {
                                    activeSponsorIds = if (sponsor.id in activeSponsorIds) {
                                        activeSponsorIds.filter { it != sponsor.id }
                                    } else {
                                        (activeSponsorIds + sponsor.id).take(6)
                                    }
                                } else {
                                    activeSponsorIds = listOf(sponsor.id)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = AppTypography.bodySmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            if (sponsorLayoutMode == SponsorLayoutMode.CAROUSEL) {
                Spacer(Modifier.height(AppSpacing.sm))
                LabeledSlider(
                    label = "Carousel interval",
                    valueText = "${sponsorCarouselIntervalSec.toInt()}s",
                    value = sponsorCarouselIntervalSec,
                    onValueChange = { sponsorCarouselIntervalSec = it },
                    valueRange = 2f..30f,
                )
            }
            Spacer(Modifier.height(AppSpacing.sm))
            Text("Sponsor display", style = AppTypography.titleSmall)
            Spacer(Modifier.height(AppSpacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                listOf(
                    SponsorDisplayMode.STATIC to "Fixed",
                    SponsorDisplayMode.SCROLL_TOP to "Scroll top",
                    SponsorDisplayMode.SCROLL_ABOVE_BOARD to "Above board",
                    SponsorDisplayMode.SCROLL_BELOW_BOARD to "Below board",
                    SponsorDisplayMode.SCROLL_BOTTOM to "Scroll bottom",
                ).forEach { (id, label) ->
                    val selected = sponsorDisplayMode == id
                    Text(
                        label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.radiusSm))
                            .background(
                                if (selected) AppColors.Accent.copy(alpha = 0.2f)
                                else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) AppColors.Accent else AppColors.Border,
                                shape = RoundedCornerShape(AppSpacing.radiusSm),
                            )
                            .clickable { sponsorDisplayMode = id }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = AppTypography.bodySmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.sm))
            LabeledSlider(
                label = "Logo size",
                valueText = "${(sponsorSizeScale * 100).toInt()}%",
                value = sponsorSizeScale,
                onValueChange = { sponsorSizeScale = it },
                valueRange = 0.3f..3f,
            )
            LabeledSlider(
                label = "Logo opacity",
                valueText = "${(sponsorOpacity * 100).toInt()}%",
                value = sponsorOpacity,
                onValueChange = { sponsorOpacity = it },
                valueRange = 0.2f..1f,
            )
            if (!sponsorScrollMode) {
                LabeledSlider(
                    label = "Horizontal position",
                    valueText = "${(sponsorPositionX * 100).toInt()}%",
                    value = sponsorPositionX,
                    onValueChange = { sponsorPositionX = it },
                    valueRange = 0f..1f,
                )
                LabeledSlider(
                    label = "Vertical position",
                    valueText = "${(sponsorPositionY * 100).toInt()}%",
                    value = sponsorPositionY,
                    onValueChange = { sponsorPositionY = it },
                    valueRange = 0f..1f,
                )
            } else {
                Text("Scroll direction", style = AppTypography.titleSmall)
                Spacer(Modifier.height(AppSpacing.xs))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    listOf(
                        uk.co.cricrelay.shared.model.SponsorScrollDirection.RTL to "Right → Left",
                        uk.co.cricrelay.shared.model.SponsorScrollDirection.LTR to "Left → Right",
                        uk.co.cricrelay.shared.model.SponsorScrollDirection.TTB to "Top → Bottom",
                        uk.co.cricrelay.shared.model.SponsorScrollDirection.BTT to "Bottom → Top",
                        uk.co.cricrelay.shared.model.SponsorScrollDirection.FIXED to "Fixed",
                    ).forEach { (id, label) ->
                        val selected = sponsorScrollDirection == id
                        Text(
                            label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppSpacing.radiusSm))
                                .background(
                                    if (selected) AppColors.Accent.copy(alpha = 0.2f)
                                    else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) AppColors.Accent else AppColors.Border,
                                    shape = RoundedCornerShape(AppSpacing.radiusSm),
                                )
                                .clickable { sponsorScrollDirection = id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = AppTypography.bodySmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.sm))
                LabeledSlider(
                    label = "Scroll speed",
                    valueText = String.format("%.1f×", sponsorScrollSpeed),
                    value = sponsorScrollSpeed,
                    onValueChange = { sponsorScrollSpeed = it },
                    valueRange = 0.3f..3f,
                )
            }
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    Text(
        "Board preset",
        style = AppTypography.titleSmall,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.sm))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        items(BoardPreset.ALL) { preset ->
            val selected = themeId == preset.id
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(
                        if (selected) AppColors.Primary.copy(alpha = 0.12f) else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                    )
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) AppColors.Primary else AppColors.Border,
                        shape = RoundedCornerShape(AppSpacing.radiusSm),
                    )
                    .clickable {
                        // Preset selection owns the board look: clear the legacy custom
                        // colours so the page's preset CSS paints unmodified (D3).
                        themeId = preset.id
                        bg = ""
                        text = ""
                    }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Two-tone chip: row-1 background fill with the preset's accent as a dot.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(parseSwatchColor(preset.row1Bg), CircleShape)
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) AppColors.Primary else Color.White.copy(alpha = 0.20f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(parseSwatchColor(preset.accent), CircleShape)
                            .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    preset.displayName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) AppColors.OnBackground else AppColors.OnBackgroundMuted,
                    maxLines = 1,
                )
            }
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    // Bowling island: the separate bowler box (figures + THIS OVER strip) beside the board.
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Bowler island", style = AppTypography.titleSmall)
                Text(
                    "Bowler figures and THIS OVER beside the board",
                    style = AppTypography.bodySmall,
                )
            }
            Switch(
                checked = bowlingIsland,
                onCheckedChange = { bowlingIsland = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.OnBackgroundDim,
                    uncheckedTrackColor = AppColors.SurfaceElevated,
                ),
            )
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        LabeledSlider(
            label = "Board width",
            valueText = "${(widthFraction * 100).toInt()}%",
            value = widthFraction,
            onValueChange = { widthFraction = it },
            valueRange = 0.25f..0.98f,
        )
        LabeledSlider(
            label = "Board height",
            valueText = "${(heightFraction * 100).toInt()}%",
            value = heightFraction,
            onValueChange = { heightFraction = it },
            valueRange = 0.10f..0.28f,
        )
        if (heightFraction <= 0.105f) {
            Text(
                "Hides batsmen strip",
                style = AppTypography.bodySmall,
                color = AppColors.OnBackgroundDim,
            )
        }
        LabeledSlider(
            label = "Font size",
            valueText = "${(fontScale * 100).toInt()}%",
            value = fontScale,
            onValueChange = { fontScale = it },
            valueRange = OverlayLayoutPrefs.FONT_MIN.toFloat()..OverlayLayoutPrefs.FONT_MAX.toFloat(),
        )
        LabeledSlider(
            label = "Opacity",
            valueText = "${(opacity * 100).toInt()}%",
            value = opacity,
            onValueChange = { opacity = it },
            valueRange = 0.2f..1.0f,
        )
        if (opacity < 0.6f) {
            Text(
                "May be hard to read in sunlight",
                style = AppTypography.bodySmall,
                color = AppColors.Warning,
            )
        }
        LabeledSlider(
            label = "Position",
            valueText = "${bottomMargin.toInt()}",
            value = bottomMargin,
            onValueChange = { bottomMargin = it },
            valueRange = 0f..48f,
        )
    }

    Spacer(Modifier.height(AppSpacing.md))

    // Watermark (admin): burned into the encoded stream, top-right.
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Stream watermark", style = AppTypography.titleSmall)
                Text(
                    "Shown top-right on the broadcast",
                    style = AppTypography.bodySmall,
                )
            }
            Switch(
                checked = watermarkEnabled,
                onCheckedChange = { watermarkEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.OnBackgroundDim,
                    uncheckedTrackColor = AppColors.SurfaceElevated,
                ),
            )
        }
        if (watermarkEnabled) {
            Spacer(Modifier.height(AppSpacing.sm))
            StudioTextField(
                value = watermarkText,
                onValueChange = { watermarkText = it },
                label = "Watermark text",
            )
        }
    }

    Spacer(Modifier.height(AppSpacing.md))
    PrimaryButton(
        text = "Save board",
        onClick = {
            onSave(buildDraftPrefs())
            onDismiss()
        },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
}

@Composable
fun ScoringSheet(
    scoring: uk.co.cricrelay.shared.model.ScoringConfig?,
    onSelectMode: (String) -> Unit,
    onOpenScorer: () -> Unit,
    onShowScorerQr: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val current = scoring?.mode
    SheetHeader(
        title = "Scoring",
        subtitle = "Switch the scoreboard's data source any time — even mid-match.",
    )
    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        listOf(
            Triple("auto", "Auto (Play-Cricket)", "Follows the club's Play-Cricket scorer"),
            Triple("auto_ch", "Auto (CricHeroes)", "Best-effort scrape from CricHeroes scorecard"),
            Triple("manual", "Manual scorer", "Score from the web scorer yourself"),
        ).forEach { (mode, label, description) ->
            SelectableOptionCard(
                title = label,
                description = description,
                selected = when (mode) {
                    "auto" -> current.equals("auto", ignoreCase = true)
                    "auto_ch" -> false
                    else -> current.equals(mode, ignoreCase = true)
                },
                onClick = {
                    when (mode) {
                        "auto_ch" -> onSelectMode("auto:cricheroes")
                        else -> onSelectMode(mode)
                    }
                },
            )
        }
    }
    scoring?.let {
        Spacer(Modifier.height(AppSpacing.md))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = AppColors.OnBackgroundDim,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.sm))
            Text("Active mode: ${it.mode}", style = AppTypography.bodySmall)
        }
        Spacer(Modifier.height(AppSpacing.sm))
        if (current.equals("manual", ignoreCase = true)) {
            SecondaryButton(
                text = "Show scorer QR",
                onClick = onShowScorerQr,
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
        }
        SecondaryButton(
            text = "Open scorer in browser",
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.scorerUrl)))
                onDismiss()
            },
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
    }
}

private const val STABILIZATION_FOV_CAPTION =
    "Strong stabilization slightly narrows the camera's field of view."

/**
 * Camera settings sheet — the camera checklist row's destination. Stabilization, orientation,
 * and keep-screen-on moved here from the old quick-toggle rail. Pre-live only: everything is
 * locked while streaming (stabilization and orientation can't change mid-RTMP).
 */
@Composable
fun CameraSettingsSheet(
    state: StudioUiState,
    onSetStabilization: (Int) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onRestartPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locked = state.streaming
    SheetHeader(
        title = "Camera",
        subtitle = if (locked) {
            "Camera settings are locked while you're live."
        } else {
            "Stabilization, orientation, and screen settings for this phone."
        },
    )

    // Stabilization: the same three levels the old quick toggle cycled through.
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Text("Stabilization", style = AppTypography.titleSmall)
        Spacer(Modifier.height(AppSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            listOf(
                StabilizationLevel.OFF to "Off",
                StabilizationLevel.STANDARD to "Standard",
                StabilizationLevel.CINEMATIC to "Cinematic",
            ).forEach { (level, label) ->
                val selected = state.overlayPrefs.stabilizationLevel == level
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .background(
                            if (selected) AppColors.Primary.copy(alpha = 0.25f)
                            else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) AppColors.Primary else AppColors.Border,
                            shape = RoundedCornerShape(AppSpacing.radiusSm),
                        )
                        .clickable(enabled = !locked) { onSetStabilization(level) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = AppTypography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) AppColors.OnBackground else AppColors.OnBackgroundMuted,
                )
            }
        }
        if (state.overlayPrefs.stabilizationLevel == StabilizationLevel.CINEMATIC) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                STABILIZATION_FOV_CAPTION,
                style = AppTypography.bodySmall,
                color = AppColors.OnBackgroundDim,
            )
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    // Orientation: one-tap flip to the opposite of what's on screen (same VM contract as the
    // old top-bar button — Auto simply follows the sensor until the first tap).
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Orientation", style = AppTypography.titleSmall)
                Text(
                    when (state.orientationMode) {
                        OrientationMode.Auto -> "Follows the phone until you lock it"
                        OrientationMode.Landscape -> "Locked to landscape"
                        OrientationMode.Portrait -> "Locked to portrait"
                    },
                    style = AppTypography.bodySmall,
                )
            }
            Text(
                "Flip",
                modifier = Modifier
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(AppColors.Accent.copy(alpha = 0.15f))
                    .border(1.dp, AppColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(AppSpacing.radiusSm))
                    .clickable(enabled = !locked, onClick = onToggleOrientation)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = AppTypography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = AppColors.Accent,
            )
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    // Keep screen on while broadcasting.
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep screen on", style = AppTypography.titleSmall)
                Text(
                    "Stops the display sleeping mid-broadcast",
                    style = AppTypography.bodySmall,
                )
            }
            Switch(
                checked = state.overlayPrefs.keepScreenOn,
                onCheckedChange = onToggleKeepScreenOn,
                enabled = !locked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.OnBackgroundDim,
                    uncheckedTrackColor = AppColors.SurfaceElevated,
                ),
            )
        }
    }

    Spacer(Modifier.height(AppSpacing.md))
    SecondaryButton(
        text = "Restart camera preview",
        enabled = !locked,
        onClick = {
            onRestartPreview()
            onDismiss()
        },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
}

@Composable
fun StudioMenuSheet(
    onRestartPreview: () -> Unit,
    onPairRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetHeader(title = "Broadcast menu")
    Spacer(Modifier.height(AppSpacing.sm))
    SecondaryButton(
        text = "Restart camera preview",
        onClick = {
            onRestartPreview()
            onDismiss()
        },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.sm))
    SecondaryButton(
        text = "Pair Remote",
        onClick = {
            onPairRemote()
            onDismiss()
        },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
}
