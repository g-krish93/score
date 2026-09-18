package uk.co.cricrelay.shared.util

/**
 * Parsed companion pairing payload from a QR / App Link / custom scheme URI.
 *
 * Accepts:
 * - `https://cricrelay.co.uk/pair?slug=…&token=…&base=…`
 * - `cricrelay://pair?slug=…&token=…&base=…`
 */
data class PairDeepLink(
    val slug: String,
    val token: String,
    val apiBase: String,
)

fun parsePairDeepLink(raw: String): PairDeepLink? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val isHttpsPair = trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("http://", ignoreCase = true)
    val isCustomPair = trimmed.startsWith("cricrelay://pair", ignoreCase = true)
    if (!isHttpsPair && !isCustomPair) return null

    if (isHttpsPair) {
        val pathStart = trimmed.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val afterHost = trimmed.indexOf('/', pathStart)
        if (afterHost < 0) return null
        val host = trimmed.substring(pathStart, afterHost).substringBefore(':').lowercase()
        if (!isAllowedPairHost(host)) return null
        val pathAndQuery = trimmed.substring(afterHost)
        val pathOnly = pathAndQuery.substringBefore('?').substringBefore('#')
        if (!pathOnly.equals("/pair", ignoreCase = true) &&
            !pathOnly.lowercase().endsWith("/pair")
        ) {
            return null
        }
    }

    val query = trimmed.substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
    if (query.isBlank()) return null

    val params = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        if (part.isBlank()) continue
        val key = decodePairQuery(part.substringBefore('='))
        val value = decodePairQuery(part.substringAfter('=', missingDelimiterValue = ""))
        if (key.isNotEmpty() && key !in params) {
            params[key] = value
        }
    }

    val slug = params["slug"].orEmpty().trim()
    val token = params["token"].orEmpty().trim()
    val apiBase = params["base"].orEmpty().trim()
    if (slug.isBlank() || token.isBlank()) return null
    return PairDeepLink(
        slug = slug,
        token = token,
        apiBase = apiBase.ifBlank { "https://cricrelay.co.uk" },
    )
}

fun buildHttpsPairUrl(publicBase: String, slug: String, token: String, apiBase: String): String {
    val site = normalizeApiBaseUrl(publicBase).trimEnd('/')
    val q = buildString {
        append("slug=")
        append(encodePairQuery(slug))
        append("&token=")
        append(encodePairQuery(token))
        append("&base=")
        append(encodePairQuery(normalizeApiBaseUrl(apiBase)))
    }
    return "$site/pair?$q"
}

private fun isAllowedPairHost(host: String): Boolean {
    val h = host.trim().lowercase()
    return h == "cricrelay.co.uk" ||
        h == "www.cricrelay.co.uk" ||
        h == "localhost" ||
        h.endsWith(".cricrelay.co.uk")
}

private fun decodePairQuery(value: String): String =
    try {
        // java.net is available on Android/JVM; KMP android+ios use expect/actual via
        // percent-decoding that works for our ASCII query payloads.
        percentDecode(value.replace('+', ' '))
    } catch (_: Exception) {
        value
    }

private fun encodePairQuery(value: String): String = percentEncode(value)

private fun percentDecode(input: String): String {
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length) {
            val hex = input.substring(i + 1, i + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                out.append(byte.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun percentEncode(input: String): String {
    val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val out = StringBuilder(input.length * 2)
    for (ch in input) {
        if (ch in allowed) {
            out.append(ch)
        } else {
            val b = ch.code and 0xff
            out.append('%')
            out.append("0123456789ABCDEF"[b shr 4])
            out.append("0123456789ABCDEF"[b and 0xf])
        }
    }
    return out.toString()
}
