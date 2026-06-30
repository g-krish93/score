package uk.co.cricrelay.mobile.feature.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SportsCricket
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import uk.co.cricrelay.mobile.ui.AppMotion
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppGradients
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.CricRelayBottomSheet
import uk.co.cricrelay.mobile.ui.DangerButton
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.GhostButton
import uk.co.cricrelay.mobile.ui.GlassPanel
import uk.co.cricrelay.mobile.ui.InfoBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.PressableIconButton
import uk.co.cricrelay.mobile.ui.PressableScale
import uk.co.cricrelay.mobile.ui.PressableTextButton
import uk.co.cricrelay.mobile.ui.rememberPulseAlpha
import uk.co.cricrelay.mobile.ui.ScreenTopBar
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.SectionLabel
import uk.co.cricrelay.mobile.ui.SelectableOptionCard
import uk.co.cricrelay.mobile.ui.SheetHeader
import uk.co.cricrelay.mobile.ui.StatusChip
import uk.co.cricrelay.mobile.ui.StreamTile
import uk.co.cricrelay.mobile.ui.BackdropMood
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.StudioHero
import uk.co.cricrelay.mobile.ui.StudioTextField
import uk.co.cricrelay.shared.model.PlatformStatus
import uk.co.cricrelay.shared.model.StreamMatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenStudio: (String) -> Unit,
    onCreateStream: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var createSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    StudioBackdrop(
        modifier = modifier,
        mood = if (state.streams.any { it.broadcast.isStreaming }) {
            BackdropMood.OnAir
        } else {
            BackdropMood.Idle
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.sm, bottom = AppSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        greeting(),
                        style = AppTypography.bodySmall.copy(color = AppColors.OnBackgroundMuted),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "CricRelay Studio",
                        style = AppTypography.headlineLarge.copy(brush = AppGradients.TitleShine),
                    )
                }
                Row {
                    PressableIconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            tint = AppColors.OnBackground,
                        )
                    }
                    Box {
                        PressableIconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "Menu",
                                tint = AppColors.OnBackground,
                            )
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = AppColors.OnBackground,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.logout(onLogout)
                                },
                            )
                        }
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f),
            ) {
                when {
                    state.loading && state.streams.isEmpty() -> LoadingState()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = AppSpacing.lg,
                            end = AppSpacing.lg,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    ) {
                        item {
                            StudioHero()
                        }
                        item {
                            GlanceRow(
                                liveCount = state.streams.count { it.broadcast.isStreaming },
                                slotsUsed = state.slotsUsed,
                                slotsTotal = state.slotsTotal,
                                linkedCount = listOf(state.youtube, state.twitch)
                                    .count { it.connected || it.ready },
                            )
                        }
                        val liveStream = state.streams.firstOrNull { it.broadcast.isStreaming }
                        if (liveStream != null) {
                            item {
                                LiveNowCard(
                                    title = liveStream.label,
                                    subtitle = liveStream.slug,
                                    onClick = { onOpenStudio(liveStream.slug) },
                                )
                            }
                        }
                        if (!state.volunteerBannerDismissed) {
                            item {
                                InfoBanner(
                                    title = "Volunteer streaming",
                                    body = "On match day, paste your YouTube or Twitch stream key in the broadcast screen. No Google login required on the phone.",
                                    onDismiss = viewModel::dismissVolunteerBanner,
                                )
                            }
                        }
                        state.error?.let { error ->
                            item { ErrorBanner(error) }
                        }
                        if (state.streams.isEmpty()) {
                            item {
                                GlassPanel {
                                    Text("No streams yet", style = AppTypography.titleMedium)
                                    Spacer(Modifier.height(AppSpacing.sm))
                                    Text(
                                        "Create a stream linked to a Play-Cricket fixture or CricHeroes scorecard.",
                                        style = AppTypography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(AppSpacing.md))
                                    PrimaryButton(text = "Create stream", onClick = { createSheet = true })
                                }
                            }
                        } else {
                            item { SectionLabel("Your streams") }
                            items(state.streams, key = { it.slug }) { stream ->
                                Box(modifier = Modifier.animateItem()) {
                                    StreamTile(
                                        title = stream.label,
                                        subtitle = stream.slug,
                                        chips = streamStatusChips(stream),
                                        highlighted = stream.broadcast.isStreaming,
                                        onClick = { onOpenStudio(stream.slug) },
                                        onLongClick = { viewModel.openManagement(stream.slug, stream.label) },
                                    )
                                }
                            }
                        }
                        item { SectionLabel("YouTube & Twitch") }
                        item {
                            InfoBanner(
                                title = "Club OAuth",
                                body = "Connect once here so match-day Go Live can use your club's YouTube or Twitch without pasting keys.",
                                accentColor = AppColors.AccentBlue,
                            )
                        }
                        item {
                            OAuthCard(
                                title = "YouTube",
                                icon = Icons.Outlined.SmartDisplay,
                                brandColor = AppColors.YouTube,
                                status = state.youtube,
                                onConnect = {
                                    scope.launch {
                                        runCatching {
                                            val url = viewModel.youtubeAuthorizeUrl()
                                            if (url.isNotBlank()) {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }
                                        }
                                    }
                                },
                                onDisconnect = viewModel::disconnectYoutube,
                            )
                        }
                        item {
                            OAuthCard(
                                title = "Twitch",
                                icon = Icons.Outlined.SportsEsports,
                                brandColor = AppColors.Twitch,
                                status = state.twitch,
                                onConnect = {
                                    scope.launch {
                                        runCatching {
                                            val url = viewModel.twitchAuthorizeUrl()
                                            if (url.isNotBlank()) {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }
                                        }
                                    }
                                },
                                onDisconnect = viewModel::disconnectTwitch,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.streams.isNotEmpty(),
                enter = fadeIn(AppMotion.enterSpec()) +
                    scaleIn(
                        initialScale = AppMotion.EnterScale,
                        animationSpec = AppMotion.enterSpec(),
                    ),
                exit = fadeOut(AppMotion.exitSpec()) +
                    scaleOut(
                        targetScale = AppMotion.ExitScale,
                        animationSpec = AppMotion.exitSpec(),
                    ),
                modifier = Modifier.align(Alignment.End),
            ) {
                FloatingActionButton(
                    onClick = { createSheet = true },
                    containerColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary,
                    modifier = Modifier.padding(AppSpacing.lg),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Create stream")
                }
            }
        }
    }

    CricRelayBottomSheet(visible = createSheet, onDismiss = { createSheet = false }) {
        SheetHeader(title = "New stream", subtitle = "Choose how scoring feeds the overlay.")
        ActionCard(
            title = "Play-Cricket fixture",
            description = "Scores follow your club's Play-Cricket scorer automatically.",
            icon = Icons.Outlined.SportsCricket,
            tint = AppColors.Accent,
            onClick = {
                createSheet = false
                onCreateStream("play_cricket")
            },
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
        Spacer(Modifier.height(AppSpacing.sm))
        ActionCard(
            title = "CricHeroes scorecard",
            description = "Best-effort auto-scrape from a CricHeroes live scorecard URL.",
            icon = Link,
            tint = AppColors.AccentBlue,
            onClick = {
                createSheet = false
                onCreateStream("cricheroes")
            },
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
    }

    CricRelayBottomSheet(
        visible = state.managementSlug != null,
        onDismiss = viewModel::closeManagement,
    ) {
        SheetHeader(title = "Manage stream", subtitle = state.managementSlug)
        StudioTextField(
            value = state.renameLabel,
            onValueChange = viewModel::onRenameLabelChange,
            label = "Stream label",
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
        Spacer(Modifier.height(AppSpacing.md))
        PrimaryButton(
            text = "Save label",
            onClick = { viewModel.renameStream(onDone = {}) },
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
        Spacer(Modifier.height(AppSpacing.sm))
        DangerButton(
            text = "Delete stream",
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(horizontal = AppSpacing.lg),
        )
    }

    if (confirmDelete && state.managementSlug != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = AppColors.SurfaceElevated,
            titleContentColor = AppColors.OnBackground,
            textContentColor = AppColors.OnBackgroundMuted,
            title = { Text("Delete this stream?") },
            text = { Text("“${state.renameLabel.ifBlank { state.managementSlug }}” and its overlay settings will be removed. This cannot be undone.") },
            confirmButton = {
                PressableTextButton(
                    onClick = {
                        confirmDelete = false
                        state.managementSlug?.let(viewModel::deleteStream)
                    },
                ) {
                    Text("Delete", color = AppColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                PressableTextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = AppColors.OnBackgroundMuted)
                }
            },
        )
    }
}

/** Full-width tappable action row with an icon tile and chevron affordance. */
@Composable
private fun ActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PressableScale(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.radiusMd))
                .background(AppColors.SurfaceElevated.copy(alpha = 0.7f))
                .border(1.dp, AppColors.Border, RoundedCornerShape(AppSpacing.radiusMd))
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(AppSpacing.radiusSm))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(description, style = AppTypography.bodySmall)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = AppColors.OnBackgroundDim,
        )
        }
    }
}

@Composable
private fun OAuthCard(
    title: String,
    icon: ImageVector,
    brandColor: Color,
    status: PlatformStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        PressableScale(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .background(brandColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = brandColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = AppTypography.titleMedium)
                    if (status.label.isNotBlank()) {
                        Text(status.label, style = AppTypography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                StatusChip(
                    label = when {
                        status.ready -> "Ready"
                        status.connected -> "Connected"
                        else -> "Not linked"
                    },
                    ok = status.ready || status.connected,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.md))
        if (status.connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                SecondaryButton(text = "Reconnect", onClick = onConnect, modifier = Modifier.weight(1f))
                DangerButton(text = "Disconnect", onClick = onDisconnect, modifier = Modifier.weight(1f))
            }
        } else {
            PrimaryButton(text = "Connect $title", onClick = onConnect)
        }
    }
}

@Composable
fun CreateStreamScreen(
    mode: String,
    onCreated: (StreamMatch) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateStreamViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            ScreenTopBar(
                title = when (mode) {
                    "cricheroes" -> "New CricHeroes stream"
                    else -> "New Play-Cricket stream"
                },
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.lg),
            ) {
                Spacer(Modifier.height(AppSpacing.sm))
                StudioTextField(
                    value = state.label,
                    onValueChange = viewModel::onLabelChange,
                    label = "Stream label",
                )
                if (mode == "play_cricket") {
                    Spacer(Modifier.height(AppSpacing.md))
                    SectionLabel("Choose a fixture")
                    state.fixtures.forEach { fixture ->
                        val alreadyLive = state.activeMatchIds.contains(fixture.matchId)
                        SelectableOptionCard(
                            title = fixture.title,
                            description = if (alreadyLive) "Already streaming" else null,
                            selected = fixture.matchId == state.selectedMatchId,
                            enabled = !alreadyLive,
                            onClick = { viewModel.onMatchSelected(fixture.matchId) },
                            icon = Icons.Outlined.SportsCricket,
                            modifier = Modifier.padding(bottom = AppSpacing.sm),
                        )
                    }
                    if (state.fixtures.isEmpty()) {
                        Text(
                            "No upcoming fixtures found for your club.",
                            style = AppTypography.bodyMedium,
                            modifier = Modifier.padding(vertical = AppSpacing.sm),
                        )
                    }
                } else if (mode == "cricheroes") {
                    Spacer(Modifier.height(AppSpacing.md))
                    StudioTextField(
                        value = state.cricheroesUrl,
                        onValueChange = viewModel::onCricheroesUrlChange,
                        label = "CricHeroes scorecard URL",
                    )
                    Text(
                        "R&D / best-effort — paste a live scorecard link from cricheroes.in",
                        style = AppTypography.bodySmall,
                        modifier = Modifier.padding(top = AppSpacing.sm),
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(AppSpacing.md))
                    ErrorBanner(it)
                }
                Spacer(Modifier.height(AppSpacing.md))
            }
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                PrimaryButton(
                    text = "Create stream",
                    loading = state.loading,
                    onClick = {
                        when (mode) {
                            "cricheroes" -> viewModel.createCricHeroes(onCreated)
                            else -> viewModel.createPlayCricket(onCreated)
                        }
                    },
                )
                Spacer(Modifier.height(AppSpacing.xs))
                GhostButton(text = "Back", onClick = onBack)
            }
        }
    }
}

private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** Animates an int from 0 up to [target] on first composition, then tracks changes. */
@Composable
private fun countUp(target: Int): Int {
    var started by remember { mutableStateOf(false) }
    val value by animateIntAsState(
        targetValue = if (started) target else 0,
        animationSpec = tween(700),
        label = "countUp",
    )
    LaunchedEffect(Unit) { started = true }
    return value
}

/** Glanceable, at-a-glance dashboard row — live count, slot usage, linked destinations. */
@Composable
private fun GlanceRow(
    liveCount: Int,
    slotsUsed: Int,
    slotsTotal: Int,
    linkedCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        GlanceCard(
            modifier = Modifier.weight(1f),
            value = countUp(liveCount).toString(),
            label = "Live now",
            accent = AppColors.Live,
            pulse = liveCount > 0,
        )
        GlanceCard(
            modifier = Modifier.weight(1f),
            value = "${countUp(slotsUsed)}/$slotsTotal",
            label = "Streams",
            accent = AppColors.Accent,
        )
        GlanceCard(
            modifier = Modifier.weight(1f),
            value = countUp(linkedCount).toString(),
            label = "Linked",
            accent = AppColors.AccentBlue,
        )
    }
}

@Composable
private fun GlanceCard(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val glow = rememberPulseAlpha(active = pulse, min = 0.5f, max = 1f, durationMs = 900, label = "glance")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(AppColors.Surface.copy(alpha = 0.85f))
            .border(1.dp, accent.copy(alpha = 0.35f * glow), RoundedCornerShape(AppSpacing.radiusLg))
            .padding(vertical = AppSpacing.md, horizontal = AppSpacing.sm),
    ) {
        Column {
            Text(
                value,
                color = accent.copy(alpha = glow),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label.uppercase(),
                style = AppTypography.bodySmall.copy(
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

/** Intent-driven surface: when something is on air, lift it to the top of the screen. */
@Composable
private fun LiveNowCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val pulse = rememberPulseAlpha(active = true, min = 0.45f, max = 1f, durationMs = 800, label = "liveNow")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(
                Brush.linearGradient(
                    listOf(
                        AppColors.Live.copy(alpha = 0.30f),
                        AppColors.SurfaceElevated.copy(alpha = 0.9f),
                    ),
                ),
            )
            .border(1.dp, AppColors.Live.copy(alpha = 0.5f * pulse), RoundedCornerShape(AppSpacing.radiusLg))
            .clickable(onClick = onClick)
            .padding(AppSpacing.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(AppColors.Live.copy(alpha = pulse)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "LIVE NOW",
                    color = AppColors.Live,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
            Spacer(Modifier.height(AppSpacing.sm))
            Text(title, style = AppTypography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = AppTypography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "Tap to open studio →",
                color = AppColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
