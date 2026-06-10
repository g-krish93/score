package uk.co.cricrelay.shared.util

fun normalizeApiBaseUrl(raw: String): String =
    raw.trim().trimEnd('/')

fun isAllowedApiBaseUrl(raw: String): Boolean {
    val trimmed = normalizeApiBaseUrl(raw)
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3)
    val host = rest.substringBefore('/').substringBefore(':').lowercase()
    if (host.isEmpty()) return false

    if (scheme == "https") return true
    if (scheme == "http") {
        if (host == "localhost" || host == "127.0.0.1") return true
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local")) {
            return true
        }
    }
    return false
}

fun resolveAbsoluteUrl(baseUrl: String, path: String): String {
    val p = path.trim()
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    val base = normalizeApiBaseUrl(baseUrl)
    return if (p.startsWith("/")) "$base$p" else "$base/$p"
}
