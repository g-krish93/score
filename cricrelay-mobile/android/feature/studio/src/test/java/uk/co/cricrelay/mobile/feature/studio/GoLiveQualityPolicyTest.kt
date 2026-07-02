package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoLiveQualityPolicyTest {

    @Test
    fun `plenty of headroom picks 1080p`() {
        assertEquals(
            GoLiveQualityPolicy.FULL_HD,
            GoLiveQualityPolicy.choose(deviceSupports1080 = true, measuredMbps = 12.0),
        )
        assertEquals(
            GoLiveQualityPolicy.FULL_HD,
            GoLiveQualityPolicy.choose(deviceSupports1080 = true, measuredMbps = 6.0),
        )
    }

    @Test
    fun `limited bandwidth drops to 720p`() {
        assertEquals(
            GoLiveQualityPolicy.HD,
            GoLiveQualityPolicy.choose(deviceSupports1080 = true, measuredMbps = 4.0),
        )
        // 0.0 = the probe timed out mid-upload: measurably slow, not unknown.
        assertEquals(
            GoLiveQualityPolicy.HD,
            GoLiveQualityPolicy.choose(deviceSupports1080 = true, measuredMbps = 0.0),
        )
    }

    @Test
    fun `unknown network keeps the prepared default`() {
        assertNull(GoLiveQualityPolicy.choose(deviceSupports1080 = true, measuredMbps = null))
    }

    @Test
    fun `720p-capped devices never re-prepare`() {
        assertNull(GoLiveQualityPolicy.choose(deviceSupports1080 = false, measuredMbps = 50.0))
        assertNull(GoLiveQualityPolicy.choose(deviceSupports1080 = false, measuredMbps = 1.0))
    }
}
