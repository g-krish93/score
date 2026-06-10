package uk.co.cricrelay.mobile.feature.studio

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.GlassPanel
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.SheetHeader
import uk.co.cricrelay.mobile.ui.StatusChip
import uk.co.cricrelay.mobile.ui.StudioTextField
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

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
    StreamDestination.entries.forEach { dest ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.destination == dest,
                onClick = { onSelect(dest) },
            )
            Text(dest.label, modifier = Modifier.weight(1f))
        }
    }
    if (state.destination == StreamDestination.Custom) {
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
    } else {
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
    onSave: (OverlayLayoutPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    var fontScale by remember { mutableStateOf(prefs.fontScale.toFloat()) }
    var opacity by remember { mutableStateOf(prefs.opacity.toFloat()) }
    var bottomMargin by remember { mutableStateOf(prefs.bottomMargin.toFloat()) }
    var bg by remember { mutableStateOf(prefs.bgColor) }
    var text by remember { mutableStateOf(prefs.textColor) }
    var overlayTheme by remember { mutableStateOf(prefs.theme.ifBlank { "classic" }) }

    SheetHeader(
        title = "Board Edit",
        subtitle = "Choose a style for the live scoreboard overlay. Applies immediately.",
    )

    Text(
        "Overlay style",
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.xs))
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AppSpacing.sm),
    ) {
        items(overlayStyles) { style ->
            val selected = overlayTheme == style.id
            Column(
                modifier = Modifier
                    .width(72.dp)
                    .background(
                        if (selected) Color(0xFF1A3060) else Color(0xFF131B2A),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color(0xFF2F7BFF) else Color.White.copy(alpha = 0.12f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    )
                    .clickable { overlayTheme = style.id }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(style.swatch, CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(style.emoji, style = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    style.label,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else Color(0xFFCBD5E1),
                    ),
                    maxLines = 1,
                )
                Text(
                    style.description,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                        color = Color(0xFF64748B),
                    ),
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(AppSpacing.md))

    Text(
        "Font size  ${(fontScale * 100).toInt()}%",
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Slider(
        value = fontScale,
        onValueChange = { fontScale = it },
        valueRange = 0.6f..2.0f,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )

    Text(
        "Opacity  ${(opacity * 100).toInt()}%",
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Slider(
        value = opacity,
        onValueChange = { opacity = it },
        valueRange = 0.2f..1.0f,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )

    Text(
        "Position  ${bottomMargin.toInt()}",
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Slider(
        value = bottomMargin,
        onValueChange = { bottomMargin = it },
        valueRange = 0f..48f,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )

    Spacer(Modifier.height(AppSpacing.md))
    PrimaryButton(
        text = "Save board",
        onClick = {
            onSave(
                prefs.copy(
                    fontScale = fontScale.toDouble(),
                    opacity = opacity.toDouble(),
                    bottomMargin = bottomMargin.toDouble(),
                    bgColor = bg,
                    textColor = text,
                    theme = overlayTheme,
                ),
            )
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
    listOf(
        "auto" to "Auto (Play-Cricket)",
        "manual" to "Manual scorer",
        "ble" to "PCS BLE (R&D)",
    ).forEach { (mode, label) ->
        val selected = current.equals(mode, ignoreCase = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectMode(mode) }
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { onSelectMode(mode) })
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
        }
    }
    scoring?.let {
        Spacer(Modifier.height(AppSpacing.sm))
        GlassPanel(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
            Text("Active mode: ${it.mode}", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(AppSpacing.sm))
            PrimaryButton(
                text = "Open scorer in browser",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.scorerUrl)))
                    onDismiss()
                },
            )
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
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        StatusChip(label = "Camera ready", ok = state.previewReady)
        Spacer(Modifier.height(AppSpacing.sm))
        StatusChip(label = state.destinationLabel, ok = state.destinationReady)
        Spacer(Modifier.height(AppSpacing.sm))
        StatusChip(label = "Scoreboard on stream", ok = state.match?.overlayEmbedUrl?.isNotBlank() == true)
    }
    Spacer(Modifier.height(AppSpacing.lg))
    PrimaryButton(
        text = "Go Live",
        enabled = state.previewReady && state.destinationReady,
        onClick = onConfirm,
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.sm))
    PrimaryButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.padding(horizontal = AppSpacing.lg))
}

@Composable
fun StudioMenuSheet(
    prefs: OverlayLayoutPrefs,
    onSave: (OverlayLayoutPrefs) -> Unit,
    onRestartPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetHeader(title = "Broadcast menu")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Video stabilization", modifier = Modifier.weight(1f))
        Switch(
            checked = prefs.videoStabilization,
            onCheckedChange = { onSave(prefs.copy(videoStabilization = it)) },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Keep screen on", modifier = Modifier.weight(1f))
        Switch(
            checked = prefs.keepScreenOn,
            onCheckedChange = { onSave(prefs.copy(keepScreenOn = it)) },
        )
    }
    Spacer(Modifier.height(AppSpacing.sm))
    PrimaryButton(
        text = "Restart camera preview",
        onClick = {
            onRestartPreview()
            onDismiss()
        },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
}
