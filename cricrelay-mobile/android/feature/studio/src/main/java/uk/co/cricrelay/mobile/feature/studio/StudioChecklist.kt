package uk.co.cricrelay.mobile.feature.studio

import uk.co.cricrelay.shared.model.StabilizationLevel

/** Which of the three go-live checks a checklist row represents (1b Checklist gate). */
enum class CheckKind { Camera, Destination, Scoring }

/**
 * One row of the go-live checklist — pure data derived from [StudioUiState] so the gate is
 * unit-testable without Compose. [warning] rows render the coral blocked-as-guidance
 * treatment (tinted row + "Choose" chip); complete rows get the sky check disc.
 */
data class StudioCheck(
    val kind: CheckKind,
    val complete: Boolean,
    val title: String,
    val sublabel: String,
    val warning: Boolean = !complete,
)

/**
 * Derivation for the 1b Checklist gate: the segmented Go Live ring and the checklist panel
 * both read from here, so "what blocks Go Live" has exactly one definition. Check 3 folds in
 * the old preflight's overlay-URL coverage — a configured scorer with no overlay embed would
 * otherwise go live with a silent, board-less stream.
 */
object StudioChecklist {

    fun deriveChecks(state: StudioUiState): List<StudioCheck> = listOf(
        cameraCheck(state),
        destinationCheck(state),
        scoringCheck(state),
    )

    fun completedCount(checks: List<StudioCheck>): Int = checks.count { it.complete }

    fun firstIncomplete(checks: List<StudioCheck>): StudioCheck? =
        checks.firstOrNull { !it.complete }

    /** Caption under the ring — names the first missing check per SPEC blocked-as-guidance. */
    fun ringCaption(checks: List<StudioCheck>): String = when (firstIncomplete(checks)?.kind) {
        null -> "All checks passed — tap to go live"
        CheckKind.Camera -> "Waiting for the camera to unlock"
        CheckKind.Destination -> "Choose a destination to unlock"
        CheckKind.Scoring -> "Choose a scoring source to unlock"
    }

    /** Coral "N to fix" line inside the blocked ring; null once every check passes. */
    fun fixLabel(checks: List<StudioCheck>): String? {
        val remaining = checks.count { !it.complete }
        return if (remaining == 0) null else "$remaining to fix"
    }

    private fun cameraCheck(state: StudioUiState): StudioCheck {
        if (!state.previewReady) {
            return StudioCheck(
                kind = CheckKind.Camera,
                complete = false,
                title = "Camera",
                sublabel = "Waiting for the preview…",
            )
        }
        val quality = if (state.supports1080p) "1080p30" else "720p30"
        val stabilization = when (state.overlayPrefs.stabilizationLevel) {
            StabilizationLevel.CINEMATIC -> "cinematic"
            StabilizationLevel.STANDARD -> "standard"
            else -> "off"
        }
        return StudioCheck(
            kind = CheckKind.Camera,
            complete = true,
            title = "Camera ready",
            sublabel = "$quality · stabilization $stabilization",
        )
    }

    private fun destinationCheck(state: StudioUiState): StudioCheck {
        if (!state.destinationReady) {
            return StudioCheck(
                kind = CheckKind.Destination,
                complete = false,
                title = "Destination",
                sublabel = "Nowhere to stream yet",
            )
        }
        val (title, sublabel) = when (state.destination) {
            StreamDestination.YouTube -> "YouTube connected" to "Club channel via OAuth"
            StreamDestination.Twitch -> "Twitch connected" to "Club channel via OAuth"
            StreamDestination.Custom -> "Custom RTMP set" to (
                if (state.selectedSavedDestinationId != null) "Saved destination ready"
                else "Server URL and stream key saved"
            )
        }
        return StudioCheck(CheckKind.Destination, complete = true, title = title, sublabel = sublabel)
    }

    private fun scoringCheck(state: StudioUiState): StudioCheck {
        val configured = state.scoring?.mode?.isNotBlank() == true
        val overlayOk = state.match?.overlayEmbedUrl?.isNotBlank() == true
        return when {
            !configured -> StudioCheck(
                kind = CheckKind.Scoring,
                complete = false,
                title = "Scoreboard source",
                sublabel = "Nothing linked yet",
            )
            // Preserves the old preflight's coverage: a linked scorer is useless if the
            // stream has no overlay embed URL to rasterize.
            !overlayOk -> StudioCheck(
                kind = CheckKind.Scoring,
                complete = false,
                title = "Scoreboard source",
                sublabel = "Overlay URL missing — check the stream setup",
            )
            else -> StudioCheck(
                kind = CheckKind.Scoring,
                complete = true,
                title = "Scoreboard source",
                sublabel = scoringSublabel(state),
            )
        }
    }

    private fun scoringSublabel(state: StudioUiState): String {
        val mode = state.matchDay?.scoringMode ?: state.scoring?.mode.orEmpty()
        val base = when {
            mode.equals("auto", ignoreCase = true) -> "Auto (Play-Cricket)"
            mode.equals("manual", ignoreCase = true) -> "Manual scorer"
            mode.isBlank() -> "Linked"
            else -> mode.replaceFirstChar { it.uppercase() }
        }
        return when {
            state.matchDay?.scoringStale == true -> "$base · updates stalled"
            state.matchDay?.scoringActive == true -> "$base · updating"
            mode.equals("manual", ignoreCase = true) &&
                state.matchDay != null -> "$base · waiting for scorer"
            else -> base
        }
    }
}
