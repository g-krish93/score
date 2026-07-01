package uk.co.cricrelay.stream

/**
 * Maps mobile Board Edit prefs to the Barlow broadcast overlay (cricket_overlay.html).
 * Layout is fixed; only colour prefs (bg/text) and ?theme= accent vary.
 */
object OverlayThemeBridge {

    /** cricket_overlay.html accent palette on #overlay (optional). */
    fun cricketOverlayTheme(@Suppress("UNUSED_PARAMETER") mobileTheme: String): String = "navy"

    fun urlWithTheme(baseUrl: String, @Suppress("UNUSED_PARAMETER") mobileTheme: String): String {
        if (baseUrl.isBlank()) return baseUrl
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
                .filter { it.first != "theme" && it.first != "boardStyle" }
        } else {
            emptyList()
        }
        val query = buildString {
            params.forEach { (key, value) ->
                if (isNotEmpty()) append('&')
                append(key).append('=').append(value)
            }
            if (isNotEmpty()) append('&')
            append("theme=navy&boardStyle=barlow")
        }
        return "$path?$query"
    }

    /** JS applied on every measure/style pass. */
    fun applyThemeScript(@Suppress("UNUSED_PARAMETER") mobileTheme: String): String {
        return """
    (function(){
      document.body.classList.add('board-barlow');
      if(typeof applyBoardStyle==='function'){ applyBoardStyle(); }
    })();
""".trimIndent()
    }
}
