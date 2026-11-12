package com.moonetix.offerwall.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    private val policy = LinkPolicy()

    private fun assertInside(url: String) = assertEquals(url, LinkAction.StayInside, policy.decide(url))

    private fun assertBrowser(url: String) = assertEquals(url, LinkAction.OpenBrowser(url), policy.decide(url))

    private fun assertBlocked(url: String?) = assertTrue("$url", policy.decide(url) is LinkAction.Block)

    @Test
    fun wallUrlsStayInside() {
        assertInside("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203?token=abc&sdk=android-1.0.0")
        assertInside("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203/go/123?sig=x")
        assertInside("https://wall.moonetix.com/")
        assertInside("https://wall.moonetix.com")
        assertInside("https://wall.moonetix.com:443/x")
        assertInside("https://wall.moonetix.com/x#frag")
    }

    @Test
    fun onlyTheMoonetixWallHostStaysInside() {
        assertEquals(setOf("wall.moonetix.com"), MoonetixUrls.WALL_HOSTS)
        assertBrowser("https://wall.example.com/eeee35416ef2e85e9b4d8203?token=abc&sdk=android-2.0.1")
        assertBrowser("https://wall.example.com/eeee35416ef2e85e9b4d8203/go/123?sig=x")
        assertFalse(policy.isWallUrl("https://WALL.EXAMPLE.COM/x"))
        assertFalse(policy.isTokenUrl("https://wall.example.com/eeee35416ef2e85e9b4d8203?token=abc"))
        assertBrowser("https://wall.moonetix.com.evil.com/")
        assertBrowser("https://evilwall.moonetix.com/")
        assertBrowser("https://moonetix.com/")
        assertBrowser("https://api.moonetix.com/v1/session")
        assertBrowser("https://wall.moonetix.com@evil.com/")
        assertBrowser("https://wall.moonetix.com:8443/")
    }

    @Test
    fun uppercaseHostAndSchemeStayInside() {
        assertInside("https://WALL.MOONETIX.COM/abc")
        assertInside("HTTPS://Wall.Moonetix.Com/abc")
    }

    @Test
    fun httpWallUrlLeavesTheApp() {
        assertBrowser("http://wall.moonetix.com/eeee35416ef2e85e9b4d8203")
        assertFalse(policy.isWallUrl("http://wall.moonetix.com/"))
    }

    @Test
    fun lookalikeHostsLeaveTheApp() {
        assertBrowser("https://wall.moonetix.com.evil.com/")
        assertBrowser("https://evil.com/?wall.moonetix.com")
        assertBrowser("https://evil.com/wall.moonetix.com")
        assertBrowser("https://evil.com/#wall.moonetix.com")
        assertBrowser("https://evilwall.moonetix.com/")
        assertBrowser("https://xwall.moonetix.com/")
        assertBrowser("https://moonetix.com/")
        assertBrowser("https://api.moonetix.com/v1/session")
        assertBrowser("https://wall.moonetix.co/")
        assertBrowser("https://wall.moonetix.com./")
    }

    @Test
    fun userinfoTricksLeaveTheApp() {
        assertBrowser("https://wall.moonetix.com@evil.com/")
        assertBrowser("https://wall.moonetix.com:443@evil.com/")
        assertBrowser("https://user:pass@wall.moonetix.com/")
        assertBrowser("https://evil.com@wall.moonetix.com/")
        assertFalse(policy.isWallUrl("https://wall.moonetix.com@evil.com/"))
    }

    @Test
    fun nonDefaultPortLeavesTheApp() {
        assertBrowser("https://wall.moonetix.com:8443/")
    }

    @Test
    fun unparseableUrlsNeverStayInside() {
        assertFalse(policy.isWallUrl("https://evil.com\\@wall.moonetix.com/"))
        assertFalse(policy.isWallUrl("https://wall.moonetix.com /x"))
        assertFalse(policy.isWallUrl(null))
        assertFalse(policy.isWallUrl(""))
    }

    @Test
    fun otherHttpsSitesOpenInBrowser() {
        assertBrowser("https://advertiser.example/landing?x=1")
        assertBrowser("http://advertiser.example/")
        assertBrowser("https://apps.apple.com/app/id123")
    }

    @Test
    fun playStoreLinks() {
        val url = "https://play.google.com/store/apps/details?id=com.example.game&referrer=abc"
        assertEquals(LinkAction.OpenPlayStore(url), policy.decide(url))
        val upper = "https://PLAY.google.com/store/apps/details?id=com.example"
        assertEquals(LinkAction.OpenPlayStore(upper), policy.decide(upper))
        val http = "http://play.google.com/store/apps/details?id=com.example"
        assertEquals(LinkAction.OpenPlayStore(http), policy.decide(http))
        assertBrowser("https://play.google.com.evil.com/store")
    }

    @Test
    fun marketAndIntentSchemes() {
        val market = "market://details?id=com.example.game"
        assertEquals(LinkAction.OpenMarket(market), policy.decide(market))
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;S.browser_fallback_url=https%3A%2F%2Fexample.com;end"
        assertEquals(LinkAction.OpenIntent(intent), policy.decide(intent))
        val upper = "INTENT://x#Intent;end"
        assertEquals(LinkAction.OpenIntent(upper), policy.decide(upper))
    }

    @Test
    fun contactSchemes() {
        assertEquals(LinkAction.OpenContact("tel:+15551234", "tel"), policy.decide("tel:+15551234"))
        assertEquals(LinkAction.OpenContact("mailto:a@b.c", "mailto"), policy.decide("mailto:a@b.c"))
        assertEquals(LinkAction.OpenContact("sms:+1555", "sms"), policy.decide("sms:+1555"))
        assertEquals(LinkAction.OpenContact("smsto:+1555", "smsto"), policy.decide("smsto:+1555"))
    }

    @Test
    fun dangerousAndUnknownSchemesAreBlocked() {
        assertBlocked("javascript:alert(1)")
        assertBlocked("JavaScript:alert(1)")
        assertBlocked(" javascript:alert(1)")
        assertBlocked("file:///data/data/com.example/shared_prefs/x.xml")
        assertBlocked("content://com.example.provider/secret")
        assertBlocked("data:text/html,<script>alert(1)</script>")
        assertBlocked("about:blank")
        assertBlocked("blob:https://wall.moonetix.com/uuid")
        assertBlocked("ftp://example.com/")
        assertBlocked("myapp://deep/link")
        assertBlocked("wall.moonetix.com/no-scheme")
        assertBlocked("//wall.moonetix.com/protocol-relative")
        assertBlocked("")
        assertBlocked("   ")
        assertBlocked(null)
    }

    @Test
    fun tokenUrlDetection() {
        assertTrue(policy.isTokenUrl("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203?token=sst_x&sdk=android-1.0.0"))
        assertTrue(policy.isTokenUrl("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203?sdk=android-1.0.0&token=sst_x"))
        assertFalse(policy.isTokenUrl("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203?user=1&sig=abc&via=sdk"))
        assertFalse(policy.isTokenUrl("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203?mytoken=1"))
        assertFalse(policy.isTokenUrl("https://evil.com/?token=sst_x"))
        assertFalse(policy.isTokenUrl("http://wall.moonetix.com/?token=sst_x"))
    }

    @Test
    fun webUrlCheckForIntentFallbacks() {
        assertTrue(policy.isWebUrl("https://example.com"))
        assertTrue(policy.isWebUrl("http://example.com"))
        assertFalse(policy.isWebUrl("javascript:alert(1)"))
        assertFalse(policy.isWebUrl("intent://x#Intent;end"))
        assertFalse(policy.isWebUrl(null))
    }

    @Test
    fun marketToWebUrl() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example&referrer=a%3Db",
            LinkPolicy.marketToWebUrl("market://details?id=com.example&referrer=a%3Db"),
        )
        assertNull(LinkPolicy.marketToWebUrl("market://search?q=x"))
        assertNull(LinkPolicy.marketToWebUrl("https://example.com/?id=x"))
    }

    @Test
    fun packageNames() {
        assertEquals("market://details?id=com.example.app", LinkPolicy.marketUrlForPackage("com.example.app"))
        assertNull(LinkPolicy.marketUrlForPackage(null))
        assertNull(LinkPolicy.marketUrlForPackage("noDots"))
        assertNull(LinkPolicy.marketUrlForPackage("com.example&x=1"))
        assertNull(LinkPolicy.marketUrlForPackage("com..example"))
        assertNull(LinkPolicy.marketUrlForPackage("1com.example"))
    }

    @Test
    fun schemeExtraction() {
        assertEquals("https", LinkPolicy.schemeOf("HTTPS://x"))
        assertEquals("intent", LinkPolicy.schemeOf("intent://x"))
        assertNull(LinkPolicy.schemeOf("/relative"))
        assertNull(LinkPolicy.schemeOf("1abc:x"))
    }
}
