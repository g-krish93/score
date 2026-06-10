package uk.co.cricrelay.shared

import uk.co.cricrelay.shared.util.isAllowedApiBaseUrl
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {
    @Test
    fun allowsHttps() {
        assertTrue(isAllowedApiBaseUrl("https://cricrelay.co.uk"))
    }

    @Test
    fun allowsLocalHttp() {
        assertTrue(isAllowedApiBaseUrl("http://localhost:5000"))
        assertTrue(isAllowedApiBaseUrl("http://192.168.1.10"))
    }

    @Test
    fun rejectsPlainHttp() {
        assertFalse(isAllowedApiBaseUrl("http://example.com"))
    }

    @Test
    fun normalizesTrailingSlash() {
        assertTrue(normalizeApiBaseUrl("https://cricrelay.co.uk/") == "https://cricrelay.co.uk")
    }
}
