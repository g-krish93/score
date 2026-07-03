package uk.co.cricrelay.stream

/**
 * Maps mobile Board Edit prefs to the broadcast overlay (cricket_overlay.html).
 * The board preset rides the ?boardStyle= query param plus an injected
 * applyBoardStyle() call; the legacy barlow board additionally honours the
 * free-colour prefs (bg/text) mapped in [OverlayWebViewCapture].
 */
object OverlayThemeBridge {

    /**
     * Board style ids understood by cricket_overlay.html. Mirrors
     * OverlayLayoutPrefs.sanitizeTheme in :shared — the streaming module has no
     * :shared dependency, so the set is duplicated here (keep the two in sync).
     */
    private val VALID_BOARD_STYLES =
        setOf("barlow", "floodlight", "chalk", "club-green", "broadcast-blue", "mono")

    /** Unknown/blank ids fall back to the Floodlight default (same rule as shared sanitizeTheme). */
    fun sanitizeBoardStyle(raw: String?): String {
        val t = raw?.trim()?.lowercase().orEmpty()
        return if (t in VALID_BOARD_STYLES) t else "floodlight"
    }

    /** cricket_overlay.html accent palette on #overlay (legacy accent; harmless on new boards). */
    fun cricketOverlayTheme(@Suppress("UNUSED_PARAMETER") mobileTheme: String): String = "navy"

    fun urlWithTheme(
        baseUrl: String,
        mobileTheme: String,
        bowlingIslandEnabled: Boolean = true,
    ): String {
        if (baseUrl.isBlank()) return baseUrl
        val style = sanitizeBoardStyle(mobileTheme)
        val qIndex = baseUrl.indexOf('?')
        val path = if (qIndex >= 0) baseUrl.substring(0, qIndex) else baseUrl
        val params = if (qIndex >= 0) {
            baseUrl.substring(qIndex + 1)
                .split('&')
                .mapNotNull { part ->
                    if (part.isBlank()) return@mapNotNull null
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
                }
                .filter { it.first != "theme" && it.first != "boardStyle" && it.first != "island" }
        } else {
            emptyList()
        }
        val query = buildString {
            params.forEach { (key, value) ->
                if (isNotEmpty()) append('&')
                append(key).append('=').append(value)
            }
            if (isNotEmpty()) append('&')
            append("theme=navy&boardStyle=").append(style)
            // The bowling island only exists on the new (floodlight-family) boards. Omitting
            // it for barlow keeps legacy URLs byte-identical to the pre-preset builds.
            if (style != "barlow") {
                append("&island=").append(if (bowlingIslandEnabled) "1" else "0")
            }
        }
        return "$path?$query"
    }

    /**
     * JS applied on every measure/style pass. Capability-guarded: a new app pointed at an
     * old server page (no #fl-root, no styled applyBoardStyle) must keep rendering the
     * barlow board exactly as before, so the fallback branch reproduces today's behaviour
     * verbatim. New pages own the barlow mapping themselves (applyBoardStyle('barlow')
     * re-adds board-barlow page-side).
     */
    fun applyThemeScript(
        mobileTheme: String,
        bowlingIslandEnabled: Boolean = true,
        compact: Boolean = false,
    ): String {
        val style = sanitizeBoardStyle(mobileTheme)
        return """
    (function(){
      if(document.getElementById('fl-root')&&typeof applyBoardStyle==='function'){
        applyBoardStyle('$style',{island:$bowlingIslandEnabled,compact:$compact});
      }else{
        document.body.classList.add('board-barlow');
        if(typeof applyBoardStyle==='function'){ applyBoardStyle(); }
      }
    })();
""".trimIndent()
    }
}
