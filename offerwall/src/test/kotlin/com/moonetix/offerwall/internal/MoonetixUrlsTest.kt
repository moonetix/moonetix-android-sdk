package com.moonetix.offerwall.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonetixUrlsTest {

    private val appId = "eeee35416ef2e85e9b4d8203"
    private val token = "sst_" + "Ab9_-".repeat(8) + "xyz"

    @Test
    fun tokenFixtureHasExpectedLength() {
        assertEquals(47, token.length)
    }

    @Test
    fun validAppIds() {
        assertTrue(MoonetixUrls.isValidAppId(appId))
        assertTrue(MoonetixUrls.isValidAppId("0123456789abcdef01234567"))
    }

    @Test
    fun invalidAppIds() {
        assertFalse(MoonetixUrls.isValidAppId(null))
        assertFalse(MoonetixUrls.isValidAppId(""))
        assertFalse(MoonetixUrls.isValidAppId("EEEE35416EF2E85E9B4D8203")) // uppercase
        assertFalse(MoonetixUrls.isValidAppId("eeee35416ef2e85e9b4d820")) // 23 chars
        assertFalse(MoonetixUrls.isValidAppId("eeee35416ef2e85e9b4d82033")) // 25 chars
        assertFalse(MoonetixUrls.isValidAppId("geee35416ef2e85e9b4d8203")) // non-hex
        assertFalse(MoonetixUrls.isValidAppId(" eeee35416ef2e85e9b4d8203"))
        assertFalse(MoonetixUrls.isValidAppId("eeee35416ef2e85e9b4d8203\n"))
        assertFalse(MoonetixUrls.isValidAppId("eeee35416ef2e85e9b4d8203/../x"))
    }

    @Test
    fun validTokens() {
        assertTrue(MoonetixUrls.isValidToken(token))
        assertTrue(MoonetixUrls.isValidToken("sst_" + "a".repeat(43)))
        assertTrue(MoonetixUrls.isValidToken("sst_" + "-".repeat(43)))
    }

    @Test
    fun invalidTokens() {
        assertFalse(MoonetixUrls.isValidToken(null))
        assertFalse(MoonetixUrls.isValidToken(""))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(42)))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(44)))
        assertFalse(MoonetixUrls.isValidToken("SST_" + "a".repeat(43)))
        assertFalse(MoonetixUrls.isValidToken("tok_" + "a".repeat(43)))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(42) + "="))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(42) + "+"))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(42) + "/"))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(42) + "&"))
        assertFalse(MoonetixUrls.isValidToken("sst_" + "a".repeat(43) + "\n"))
    }

    @Test
    fun buildsWallUrl() {
        assertEquals(
            "https://wall.moonetix.com/$appId?token=$token&sdk=android-1.0.0",
            MoonetixUrls.buildWallUrl(appId, token, "1.0.0"),
        )
    }

    @Test
    fun builtUrlIsAWallTokenUrl() {
        val url = MoonetixUrls.buildWallUrl(appId, token, "1.0.0")
        val policy = LinkPolicy()
        assertTrue(policy.isWallUrl(url))
        assertTrue(policy.isTokenUrl(url))
        assertEquals(LinkAction.StayInside, policy.decide(url))
    }

    @Test
    fun sdkVersionIsSentToTheWall() {
        assertEquals("2.0.1", com.moonetix.offerwall.Moonetix.VERSION)
        assertTrue(MoonetixUrls.buildWallUrl(appId, token, com.moonetix.offerwall.Moonetix.VERSION).endsWith("&sdk=android-2.0.1"))
    }

    @Test
    fun buildEncodesSdkVersion() {
        assertEquals(
            "https://wall.moonetix.com/$appId?token=$token&sdk=android-1.0.0-beta%2B1%20x",
            MoonetixUrls.buildWallUrl(appId, token, "1.0.0-beta+1 x"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsInvalidAppId() {
        MoonetixUrls.buildWallUrl("not-an-id", token, "1.0.0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsInvalidToken() {
        MoonetixUrls.buildWallUrl(appId, "sst_short", "1.0.0")
    }

    @Test
    fun encodeQueryValue() {
        assertEquals("abcXYZ019-_.~", MoonetixUrls.encodeQueryValue("abcXYZ019-_.~"))
        assertEquals("a%20b%26c%3Dd%2Fe%3Ff%23g%2Bh", MoonetixUrls.encodeQueryValue("a b&c=d/e?f#g+h"))
        assertEquals("%C3%A9%E2%82%AC", MoonetixUrls.encodeQueryValue("é€"))
        assertEquals("%25", MoonetixUrls.encodeQueryValue("%"))
        assertEquals("", MoonetixUrls.encodeQueryValue(""))
    }
}
