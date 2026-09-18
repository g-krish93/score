package uk.co.cricrelay.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairDeepLinkTest {
    @Test
    fun parsesCustomScheme() {
        val link = parsePairDeepLink(
            "cricrelay://pair?slug=oak-vs-elm&token=abc123&base=https://cricrelay.co.uk",
        )
        assertNotNull(link)
        assertEquals("oak-vs-elm", link.slug)
        assertEquals("abc123", link.token)
        assertEquals("https://cricrelay.co.uk", link.apiBase)
    }

    @Test
    fun parsesHttpsAppLink() {
        val link = parsePairDeepLink(
            "https://cricrelay.co.uk/pair?slug=demo&token=tok&base=https://cricrelay.co.uk",
        )
        assertNotNull(link)
        assertEquals("demo", link.slug)
        assertEquals("tok", link.token)
    }

    @Test
    fun rejectsNonPairHttps() {
        assertNull(parsePairDeepLink("https://cricrelay.co.uk/pricing"))
        assertNull(parsePairDeepLink("https://example.com/pair?slug=a&token=b"))
    }

    @Test
    fun buildHttpsPairUrlEncodesQuery() {
        val url = buildHttpsPairUrl(
            publicBase = "https://cricrelay.co.uk/",
            slug = "a b",
            token = "t/1",
            apiBase = "https://cricrelay.co.uk",
        )
        assertEquals(
            "https://cricrelay.co.uk/pair?slug=a%20b&token=t%2F1&base=https%3A%2F%2Fcricrelay.co.uk",
            url,
        )
    }
}
