package uk.co.cricrelay.mobile.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.BroadcastStatus
import uk.co.cricrelay.shared.model.StreamMatch

/**
 * Entity ↔ domain mapping for the offline stream cache. A full DAO round-trip through an
 * in-memory Room database needs an Android runtime (instrumented test or Robolectric), which
 * the CI `test` task doesn't provide — the SQL layer is Room-generated, so the risk lives in
 * this hand-written mapping and is covered here.
 */
class StreamEntityMappingTest {

    private val domain = StreamMatch(
        slug = "village-vs-town",
        label = "Village vs Town",
        overlayEmbedUrl = "https://club.example.com/overlay/village-vs-town",
        relaySource = "play_cricket",
        relayPaused = true,
        scoringMode = "auto",
        scoringActive = true,
        scoringStale = true,
        isLive = true,
        broadcast = BroadcastStatus(status = "streaming"),
    )

    @Test
    fun `toEntity then toDomain round-trips every cached field`() {
        val roundTripped = domain.toEntity().toDomain()
        assertEquals(domain, roundTripped)
    }

    @Test
    fun `entity carries all domain fields`() {
        val entity = domain.toEntity()
        assertEquals("village-vs-town", entity.slug)
        assertEquals("Village vs Town", entity.label)
        assertEquals("https://club.example.com/overlay/village-vs-town", entity.overlayEmbedUrl)
        assertEquals("play_cricket", entity.relaySource)
        assertTrue(entity.relayPaused)
        assertEquals("auto", entity.scoringMode)
        assertTrue(entity.scoringActive)
        assertTrue(entity.scoringStale)
        assertTrue(entity.isLive)
        assertEquals("streaming", entity.broadcastStatus)
        // cachedAt defaults to "now" so getAll() can order newest-first.
        assertTrue(entity.cachedAt > 0)
    }

    @Test
    fun `only the broadcast status string survives the cache`() {
        // The cache exists to reopen the studio offline; platform/watch-url are re-fetched
        // live. If a future field needs to survive, this test documents the current contract.
        val rich = domain.copy(
            broadcast = BroadcastStatus(
                status = "streaming",
                platform = "youtube",
                watchUrl = "https://youtu.be/x",
            ),
        )
        val restored = rich.toEntity().toDomain()
        assertEquals("streaming", restored.broadcast.status)
        assertTrue(restored.broadcast.isStreaming)
        assertNull(restored.broadcast.platform)
        assertNull(restored.broadcast.watchUrl)
    }

    @Test
    fun `idle defaults round-trip cleanly`() {
        val idle = StreamMatch(slug = "s", label = "s")
        val restored = idle.toEntity().toDomain()
        assertEquals(idle, restored)
        assertFalse(restored.broadcast.isStreaming)
        assertFalse(restored.paused)
    }
}
