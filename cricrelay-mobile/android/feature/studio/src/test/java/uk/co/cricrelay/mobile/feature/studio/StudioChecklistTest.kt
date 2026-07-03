package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.BroadcastStatus
import uk.co.cricrelay.shared.model.MatchDayStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.StabilizationLevel
import uk.co.cricrelay.shared.model.StreamMatch

class StudioChecklistTest {

    private val match = StreamMatch(
        slug = "village-vs-town",
        label = "Village vs Town",
        overlayEmbedUrl = "https://club.example.com/overlay/village-vs-town",
    )

    private fun scoring(mode: String = "manual") = ScoringConfig(
        mode = mode,
        manualInputUrl = "https://club.example.com/m/village-vs-town/input",
        manualScorerUrl = "https://club.example.com/m/village-vs-town/score",
        pcsIngestUrl = "",
        pcsIngestToken = "",
        pcsRelayApkUrl = "",
    )

    private fun readyState() = StudioUiState(
        loading = false,
        match = match,
        previewReady = true,
        destination = StreamDestination.YouTube,
        destinationReady = true,
        scoring = scoring(),
    )

    // ── completeness ────────────────────────────────────────────────────────

    @Test
    fun `all three checks pass on a fully set up studio`() {
        val checks = StudioChecklist.deriveChecks(readyState())
        assertEquals(3, StudioChecklist.completedCount(checks))
        assertNull(StudioChecklist.firstIncomplete(checks))
        assertNull(StudioChecklist.fixLabel(checks))
        assertTrue(checks.none { it.warning })
    }

    @Test
    fun `camera check fails while the preview is down`() {
        val checks = StudioChecklist.deriveChecks(readyState().copy(previewReady = false))
        assertEquals(CheckKind.Camera, StudioChecklist.firstIncomplete(checks)?.kind)
        assertEquals(2, StudioChecklist.completedCount(checks))
        assertEquals("1 to fix", StudioChecklist.fixLabel(checks))
    }

    @Test
    fun `destination check fails without a ready destination`() {
        val checks = StudioChecklist.deriveChecks(readyState().copy(destinationReady = false))
        assertEquals(CheckKind.Destination, StudioChecklist.firstIncomplete(checks)?.kind)
        assertTrue(checks.first { it.kind == CheckKind.Destination }.warning)
    }

    @Test
    fun `scoring check fails when nothing is linked`() {
        val checks = StudioChecklist.deriveChecks(readyState().copy(scoring = null))
        val scoringCheck = checks.first { it.kind == CheckKind.Scoring }
        assertFalse(scoringCheck.complete)
        assertEquals("Nothing linked yet", scoringCheck.sublabel)
    }

    @Test
    fun `scoring check fails when the overlay embed url is blank`() {
        // Preserves the old preflight's coverage: linked scoring, board-less stream.
        val state = readyState().copy(match = match.copy(overlayEmbedUrl = ""))
        val checks = StudioChecklist.deriveChecks(state)
        val scoringCheck = checks.first { it.kind == CheckKind.Scoring }
        assertFalse(scoringCheck.complete)
        assertTrue(scoringCheck.sublabel.contains("Overlay URL missing"))
    }

    @Test
    fun `fix label counts every incomplete check`() {
        val checks = StudioChecklist.deriveChecks(
            readyState().copy(previewReady = false, destinationReady = false, scoring = null),
        )
        assertEquals("3 to fix", StudioChecklist.fixLabel(checks))
        assertEquals(0, StudioChecklist.completedCount(checks))
    }

    // ── ring caption ────────────────────────────────────────────────────────

    @Test
    fun `ring caption names the first incomplete check`() {
        assertEquals(
            "Waiting for the camera to unlock",
            StudioChecklist.ringCaption(
                StudioChecklist.deriveChecks(readyState().copy(previewReady = false)),
            ),
        )
        assertEquals(
            "Choose a destination to unlock",
            StudioChecklist.ringCaption(
                StudioChecklist.deriveChecks(readyState().copy(destinationReady = false)),
            ),
        )
        assertEquals(
            "Choose a scoring source to unlock",
            StudioChecklist.ringCaption(
                StudioChecklist.deriveChecks(readyState().copy(scoring = null)),
            ),
        )
        assertEquals(
            "All checks passed — tap to go live",
            StudioChecklist.ringCaption(StudioChecklist.deriveChecks(readyState())),
        )
    }

    // ── sublabels ───────────────────────────────────────────────────────────

    @Test
    fun `camera sublabel carries quality and stabilization`() {
        val state = readyState().copy(
            supports1080p = true,
            overlayPrefs = OverlayLayoutPrefs().withStabilizationLevel(StabilizationLevel.STANDARD),
        )
        assertEquals(
            "1080p30 · stabilization standard",
            StudioChecklist.deriveChecks(state).first { it.kind == CheckKind.Camera }.sublabel,
        )

        val lowTier = state.copy(
            supports1080p = false,
            overlayPrefs = OverlayLayoutPrefs().withStabilizationLevel(StabilizationLevel.CINEMATIC),
        )
        assertEquals(
            "720p30 · stabilization cinematic",
            StudioChecklist.deriveChecks(lowTier).first { it.kind == CheckKind.Camera }.sublabel,
        )
    }

    @Test
    fun `destination sublabel follows the chosen destination`() {
        val youtube = StudioChecklist.deriveChecks(readyState())
            .first { it.kind == CheckKind.Destination }
        assertEquals("YouTube connected", youtube.title)

        val custom = StudioChecklist.deriveChecks(
            readyState().copy(destination = StreamDestination.Custom),
        ).first { it.kind == CheckKind.Destination }
        assertEquals("Custom RTMP set", custom.title)
    }

    @Test
    fun `scoring sublabel reflects the match-day status`() {
        val matchDay = MatchDayStatus(
            slug = match.slug,
            label = match.label,
            scoringMode = "auto",
            scoringActive = true,
            scoringStale = false,
            relayPaused = false,
            broadcast = BroadcastStatus(),
        )
        val active = StudioChecklist.deriveChecks(readyState().copy(matchDay = matchDay))
            .first { it.kind == CheckKind.Scoring }
        assertEquals("Auto (Play-Cricket) · updating", active.sublabel)

        val stale = StudioChecklist.deriveChecks(
            readyState().copy(matchDay = matchDay.copy(scoringActive = false, scoringStale = true)),
        ).first { it.kind == CheckKind.Scoring }
        assertEquals("Auto (Play-Cricket) · updates stalled", stale.sublabel)
    }
}
