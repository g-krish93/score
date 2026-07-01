package uk.co.cricrelay.mobile.feature.studio

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
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
import uk.co.cricrelay.mobile.ui.GhostButton
import uk.co.cricrelay.mobile.ui.LabeledSlider
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.SelectableOptionCard
import uk.co.cricrelay.mobile.ui.SheetHeader
import uk.co.cricrelay.mobile.ui.StudioTextField
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.SponsorDisplayMode

@Composable
fun DestinationSheet(
    state: StudioUiState,
    onSaveCustom: (String, String, String) -> Unit,
    onSelect: (StreamDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var rtmpUrl by remember(state.customRtmpUrl) { mutableStateOf(state.customRtmpUrl) }
    var streamKey by remember(state.customStreamKey) { mutableStateOf(state.customStreamKey) }
    var watchUrl by remember(state.customWatchUrl) { mutableStateOf(state.customWatchUrl) }

    SheetHeader(
        title = "Stream destination",
        subtitle = "Volunteers paste a YouTube Studio or Twitch stream key. Clubs can use OAuth.",
    )
    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
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
            title = "Custom RTMP",
            description = "Paste any server URL and stream key",
            icon = Icons.Outlined.VpnKey,
            iconTint = AppColors.Accent,
            selected = state.destination == StreamDestination.Custom,
            onClick = { onSelect(StreamDestination.Custom) },
        )
    }
    AnimatedVisibility(
        visible = state.destination == StreamDestination.Custom,
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
            Spacer(Modifier.height(AppSpacing.md))
            PrimaryButton(
                text = "Save stream key",
                onClick = {
                    onSaveCustom(rtmpUrl, streamKey, watchUrl)
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

/** Overlay visual style sent to the web scoreboard as the `theme` value. */
private data class OverlayStyle(
    val id: String,
    val emoji: String,
    val label: String,
    val description: String,
    val swatch: Color,
)

private val overlayStyles = listOf(
    OverlayStyle("classic",  "🏏", "Broadcast", "Sky/ESPN blue",        Color(0xFF1A3683)),
    OverlayStyle("compact",  "📋", "Compact",   "Slim ticker bar",      Color(0xFF080E28)),
    OverlayStyle("ai",       "🤖", "AI Neural", "Purple glassmorphism", Color(0xFF3B0764)),
    OverlayStyle("stadium",  "🏟", "Stadium",   "T20 night amber",      Color(0xFF1C0F00)),
    OverlayStyle("neon",     "⚡", "Neon",       "Cyan glow",            Color(0xFF060B1A)),
    OverlayStyle("minimal",  "⬜", "Minimal",    "Clean dark",           Color(0xFF111111)),
)

/** A named scoreboard color theme. Empty strings mean "use the web overlay's own colors". */
private data class BoardTheme(val name: String, val bg: String, val text: String, val swatch: Color)

private val boardThemes = listOf(
    BoardTheme("Default", "", "", Color(0xFF243140)),
    BoardTheme("Dark", "#0E1A24", "#FFFFFF", Color(0xFF0E1A24)),
    BoardTheme("Black", "#000000", "#FFFFFF", Color(0xFF000000)),
    BoardTheme("Light", "#FFFFFF", "#0E1A24", Color(0xFFFFFFFF)),
    BoardTheme("Teal", "#0B3D3A", "#7CF6D6", Color(0xFF0B3D3A)),
)

@Composable
fun OverlaySheet(
    prefs: OverlayLayoutPrefs,
    sponsors: List<Sponsor>,
    onPreview: (OverlayLayoutPrefs) -> Unit,
    onSave: (OverlayLayoutPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    var fontScale by remember { mutableStateOf(prefs.fontScale.toFloat()) }
    var widthFraction by remember { mutableStateOf(prefs.clampedWidthFraction().toFloat()) }
    var heightFraction by remember { mutableStateOf(prefs.clampedHeightFraction().toFloat()) }
    var opacity by remember { mutableStateOf(prefs.opacity.toFloat()) }
    var bottomMargin by remember { mutableStateOf(prefs.bottomMargin.toFloat()) }
    var bg by remember { mutableStateOf(prefs.bgColor) }
    var text by remember { mutableStateOf(prefs.textColor) }
    var overlayTheme by remember { mutableStateOf(prefs.theme.ifBlank { "classic" }) }
    var watermarkEnabled by remember { mutableStateOf(prefs.watermarkEnabled) }
    var watermarkText by remember { mutableStateOf(prefs.watermarkText) }
    var sponsorEnabled by remember { mutableStateOf(prefs.sponsorEnabled) }
    var activeSponsorId by remember { mutableStateOf(prefs.activeSponsorId) }
    var sponsorDisplayMode by remember { mutableStateOf(prefs.sponsorDisplayMode) }
    var sponsorPositionX by remember { mutableStateOf(prefs.sponsorPositionX.toFloat()) }
    var sponsorPositionY by remember { mutableStateOf(prefs.sponsorPositionY.toFloat()) }
    var sponsorSizeScale by remember { mutableStateOf(prefs.sponsorSizeScale.toFloat()) }
    var sponsorOpacity by remember { mutableStateOf(prefs.sponsorOpacity.toFloat()) }
    var sponsorScrollSpeed by remember { mutableStateOf(prefs.sponsorScrollSpeed.toFloat()) }
    val sponsorScrollMode = SponsorDisplayMode.isScroll(sponsorDisplayMode)

    fun buildDraftPrefs(): OverlayLayoutPrefs = prefs.copy(
        widthFraction = widthFraction.toDouble(),
        heightFraction = heightFraction.toDouble(),
        fontScale = fontScale.toDouble(),
        opacity = opacity.toDouble(),
        bottomMargin = bottomMargin.toDouble(),
        bgColor = bg,
        textColor = text,
        theme = overlayTheme,
        watermarkEnabled = watermarkEnabled,
        watermarkText = watermarkText.trim().ifBlank { OverlayLayoutPrefs.WATERMARK_DEFAULT_TEXT },
        sponsorEnabled = sponsorEnabled,
        activeSponsorId = activeSponsorId?.takeIf { sponsorEnabled },
        sponsorDisplayMode = sponsorDisplayMode,
        sponsorPositionX = sponsorPositionX.toDouble(),
        sponsorPositionY = sponsorPositionY.toDouble(),
        sponsorSizeScale = sponsorSizeScale.toDouble(),
        sponsorOpacity = sponsorOpacity.toDouble(),
        sponsorScrollSpeed = sponsorScrollSpeed.toDouble(),
    )

    LaunchedEffect(
        fontScale,
        widthFraction,
        heightFraction,
        opacity,
        bottomMargin,
        overlayTheme,
        watermarkEnabled,
        watermarkText,
        sponsorEnabled,
        activeSponsorId,
        sponsorDisplayMode,
        sponsorPositionX,
        sponsorPositionY,
        sponsorSizeScale,
        sponsorOpacity,
        sponsorScrollSpeed,
    ) {
        delay(80)
        onPreview(buildDraftPrefs())
    }

    SheetHeader(
        title = "Board Edit",
        subtitle = "Choose a style for the live scoreboard overlay. Applies immediately.",
    )

    Text(
        "Overlay style",
        style = AppTypography.titleSmall,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.sm))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        items(overlayStyles) { style ->
            val selected = overlayTheme == style.id
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(
                        if (selected) AppColors.Accent.copy(alpha = 0.12f) else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                    )
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) AppColors.Accent.copy(alpha = 0.8f) else AppColors.Border,
                        shape = RoundedCornerShape(AppSpacing.radiusSm),
                    )
                    .clickable { overlayTheme = style.id }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(style.swatch, CircleShape)
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) AppColors.Accent else Color.White.copy(alpha = 0.20f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(style.emoji, fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    style.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) AppColors.OnBackground else AppColors.OnBackgroundMuted,
                    maxLines = 1,
                )
                Text(
                    style.description,
                    fontSize = 8.sp,
                    color = AppColors.OnBackgroundDim,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sponsor logo", style = AppTypography.titleSmall)
                Text(
                    "On-stream sponsor graphics — fixed or scrolling",
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
        if (sponsorEnabled && sponsors.any { it.isActive }) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "Select sponsor for this match",
                style = AppTypography.bodySmall,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                items(sponsors.filter { it.isActive }) { sponsor ->
                    val selected = activeSponsorId == sponsor.id ||
                        (activeSponsorId.isNullOrBlank() && sponsor.id == sponsors.firstOrNull { it.isActive }?.id)
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
                            .clickable { activeSponsorId = sponsor.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = AppTypography.bodySmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        } else if (sponsorEnabled && sponsors.none { it.isActive }) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "No sponsors yet — upload logos in the web dashboard under Sponsor logos.",
                style = AppTypography.bodySmall,
                color = AppColors.Warning,
            )
        }
        if (sponsorEnabled) {
            Spacer(Modifier.height(AppSpacing.md))
            Text("Sponsor display", style = AppTypography.titleSmall)
            Spacer(Modifier.height(AppSpacing.sm))
            val modes = listOf(
                SponsorDisplayMode.STATIC to "Fixed",
                SponsorDisplayMode.SCROLL_TOP to "Scroll top",
                SponsorDisplayMode.SCROLL_ABOVE_BOARD to "Above board",
                SponsorDisplayMode.SCROLL_BELOW_BOARD to "Below board",
                SponsorDisplayMode.SCROLL_BOTTOM to "Scroll bottom",
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                items(modes) { (id, label) ->
                    val selected = sponsorDisplayMode == id
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

/** One row of the pre-flight checklist: pass/fail icon, label, hint when failing. */
@Composable
private fun PreflightRow(label: String, ok: Boolean, index: Int, hint: String? = null) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 90L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(AppMotion.enterSpec(AppMotion.SheetEnterMs)) +
            slideInVertically(
                animationSpec = tween(AppMotion.SheetEnterMs, easing = AppMotion.EaseOut),
            ) { it / 4 },
    ) {
        PreflightRowContent(label = label, ok = ok, hint = hint)
    }
}

@Composable
private fun PreflightRowContent(label: String, ok: Boolean, hint: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(
                (if (ok) AppColors.Success else AppColors.Warning).copy(alpha = 0.08f),
            )
            .padding(horizontal = AppSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (ok) AppColors.Success else AppColors.Warning,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(AppSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = AppTypography.titleSmall)
            if (!ok && hint != null) {
                Text(hint, style = AppTypography.bodySmall)
            }
        }
    }
}

@Composable
fun PreflightSheet(
    state: StudioUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetHeader(
        title = "Ready to go live?",
        subtitle = "The scoreboard appears at the bottom of your stream.",
    )
    Column(
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        PreflightRow(
            label = "Camera ready",
            ok = state.previewReady,
            index = 0,
            hint = "Wait for the preview, or restart it from the menu",
        )
        PreflightRow(
            label = state.destinationLabel,
            ok = state.destinationReady,
            index = 1,
            hint = "Set a destination or paste a stream key first",
        )
        PreflightRow(
            label = "Scoreboard on stream",
            ok = state.match?.overlayEmbedUrl?.isNotBlank() == true,
            index = 2,
            hint = "Overlay URL missing — check the stream setup",
        )
    }
    Spacer(Modifier.height(AppSpacing.lg))
    PrimaryButton(
        text = "Go Live",
        enabled = state.previewReady && state.destinationReady,
        onClick = onConfirm,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.xs))
    GhostButton(
        text = "Cancel",
        onClick = onDismiss,
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
