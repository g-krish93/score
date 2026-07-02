package uk.co.cricrelay.mobile.feature.studio

/**
 * Picks the Go Live resolution from a real upload measurement. Resolution is fixed once the
 * broadcast starts (mid-stream re-prepare is the golden-path crash), so this runs exactly once,
 * at Go Live: plenty of measured headroom → 1080p, otherwise 720p. The live BitrateAdapter
 * handles in-stream dips from there.
 */
object GoLiveQualityPolicy {

    // 1080p streams at 4.5 Mbps; require ~1.3x headroom so the encoder isn't instantly congested.
    const val MIN_MBPS_FOR_1080P = 6.0

    data class Choice(val width: Int, val height: Int, val bitrateBps: Int)

    val FULL_HD = Choice(1920, 1080, 4_500_000)
    val HD = Choice(1280, 720, 2_500_000)

    /**
     * @param deviceSupports1080 whether the phone's tier captures 1080p at all.
     * @param measuredMbps upload probe result — null means the probe couldn't run (network
     * quality unknown), 0.0 means it timed out (measurably slow).
     * @return the quality to prepare, or null to leave the current prepared quality alone.
     */
    fun choose(deviceSupports1080: Boolean, measuredMbps: Double?): Choice? {
        if (!deviceSupports1080) return null // already capped at 720p by the device tier
        if (measuredMbps == null) return null // unknown network: don't downgrade on no evidence
        return if (measuredMbps >= MIN_MBPS_FOR_1080P) FULL_HD else HD
    }
}
