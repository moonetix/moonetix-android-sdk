package com.moonetix.offerwall.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandParserTest {

    private val live = """{"ok":true,"title":"Example Rewards","logo_url":"https://moonetix.com/uploads/apps/app42_logo_0f1e2d3c4b5a6978.webp","colors":{"background":"#000000","text":"#FFFFFF","primary":"#2D69FA"}}"""

    @Test
    fun parsesLiveResponse() {
        val brand = BrandParser.parse(live)
        assertEquals(
            Brand(
                title = "Example Rewards",
                logoUrl = "https://moonetix.com/uploads/apps/app42_logo_0f1e2d3c4b5a6978.webp",
                background = 0xFF000000.toInt(),
                text = 0xFFFFFFFF.toInt(),
                primary = 0xFF2D69FA.toInt(),
            ),
            brand,
        )
    }

    @Test
    fun nullLogoAndMissingFields() {
        assertEquals(Brand(null, null, null, null, null), BrandParser.parse("""{"ok":true}"""))
        val brand = BrandParser.parse("""{"ok":true,"title":"X","logo_url":null,"colors":{"background":"#112233"}}""")!!
        assertEquals("X", brand.title)
        assertNull(brand.logoUrl)
        assertEquals(0xFF112233.toInt(), brand.background)
        assertNull(brand.text)
        assertNull(brand.primary)
    }

    @Test
    fun notOkIsRejected() {
        assertNull(BrandParser.parse("""{"ok":false}"""))
        assertNull(BrandParser.parse("""{"ok":"true","title":"X"}"""))
        assertNull(BrandParser.parse("""{"ok":1,"title":"X"}"""))
        assertNull(BrandParser.parse("""{"title":"X"}"""))
    }

    @Test
    fun invalidJsonIsRejected() {
        assertNull(BrandParser.parse(null))
        assertNull(BrandParser.parse(""))
        assertNull(BrandParser.parse("not json"))
        assertNull(BrandParser.parse("[1,2,3]"))
        assertNull(BrandParser.parse("<html>404</html>"))
        assertNull(BrandParser.parse("""{"ok":true,"""))
    }

    @Test
    fun wrongTypesAreIgnored() {
        val brand = BrandParser.parse(
            """{"ok":true,"title":123,"logo_url":false,"colors":{"background":0,"text":["#fff"],"primary":{"a":1}}}""",
        )
        assertEquals(Brand(null, null, null, null, null), brand)
        assertEquals(Brand(null, null, null, null, null), BrandParser.parse("""{"ok":true,"colors":"#000000"}"""))
    }

    @Test
    fun disallowedLogoIsDropped() {
        val brand = BrandParser.parse("""{"ok":true,"title":"X","logo_url":"https://evil.com/uploads/x.png"}""")!!
        assertEquals("X", brand.title)
        assertNull(brand.logoUrl)
    }

    @Test
    fun oversizedBodyIsRejected() {
        val padding = "a".repeat(BrandParser.MAX_JSON_BYTES)
        assertNull(BrandParser.parse("""{"ok":true,"title":"X","pad":"$padding"}"""))
        val justUnder = """{"ok":true,"title":"X","pad":""}"""
        assertNotNull(BrandParser.parse(justUnder))
    }

    @Test
    fun titlesAreCleaned() {
        assertEquals("Example", BrandParser.cleanTitle("  Example \n"))
        assertEquals("AB", BrandParser.cleanTitle("A\u0000B"))
        assertNull(BrandParser.cleanTitle("   "))
        assertNull(BrandParser.cleanTitle(null))
        assertEquals(80, BrandParser.cleanTitle("x".repeat(500))!!.length)
    }

    @Test
    fun colors() {
        assertEquals(0xFF000000.toInt(), BrandParser.parseColor("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), BrandParser.parseColor("#FFFFFF"))
        assertEquals(0xFF2D69FA.toInt(), BrandParser.parseColor("#2d69fa"))
        assertEquals(0xFFAABBCC.toInt(), BrandParser.parseColor("#abc"))
        assertEquals(0xFF2D69FA.toInt(), BrandParser.parseColor(" #2D69FA "))
        assertNull(BrandParser.parseColor(null))
        assertNull(BrandParser.parseColor(""))
        assertNull(BrandParser.parseColor("2D69FA"))
        assertNull(BrandParser.parseColor("#2D69F"))
        assertNull(BrandParser.parseColor("#802D69FA"))
        assertNull(BrandParser.parseColor("#GGGGGG"))
        assertNull(BrandParser.parseColor("red"))
        assertNull(BrandParser.parseColor("rgb(0,0,0)"))
    }

    @Test
    fun allowedLogoUrls() {
        assertTrue(BrandParser.isAllowedLogoUrl("https://moonetix.com/uploads/apps/app42_logo.webp"))
        assertTrue(BrandParser.isAllowedLogoUrl("https://wall.moonetix.com/uploads/a.png"))
        assertTrue(BrandParser.isAllowedLogoUrl("https://MOONETIX.COM/uploads/a.jpg"))
        assertTrue(BrandParser.isAllowedLogoUrl("https://moonetix.com:443/uploads/a.gif"))
        assertTrue(BrandParser.isAllowedLogoUrl("https://moonetix.com/uploads/a.png?v=2"))
    }

    @Test
    fun logoHostsAreTheMoonetixHostsOnly() {
        assertEquals(setOf("moonetix.com", "wall.moonetix.com"), BrandParser.LOGO_HOSTS)
    }

    @Test
    fun rejectedLogoUrls() {
        listOf(
            null,
            "",
            "http://moonetix.com/uploads/a.png",
            "HTTPS://moonetix.com/uploads/a.png",
            "https://moonetix.com.evil.com/uploads/a.png",
            "https://evil.com/uploads/x.png",
            "https://example.com/uploads/brand/mark.webp",
            "https://wall.example.com/uploads/brand/mark.webp",
            "https://evil.com/?https://moonetix.com/uploads/x.png",
            "https://api.moonetix.com/uploads/a.png",
            "https://www.moonetix.com/uploads/a.png",
            "https://moonetix.com@evil.com/uploads/a.png",
            "https://user@moonetix.com/uploads/a.png",
            "https://moonetix.com:8443/uploads/a.png",
            "https://moonetix.com/a.png",
            "https://moonetix.com/",
            "https://moonetix.com/uploads/",
            "https://moonetix.com/uploadsx/a.png",
            "https://moonetix.com/app/uploads/a.png",
            "https://moonetix.com/uploads/../app/secret.png",
            "https://moonetix.com/uploads/%2e%2e/a.png",
            "https://moonetix.com/uploads/..%2Fa.png",
            "https://moonetix.com/uploads//evil.com/a.png",
            "https://moonetix.com/uploads/a b.png",
            " https://moonetix.com/uploads/a.png",
            "//moonetix.com/uploads/a.png",
            "data:image/png;base64,AAAA",
            "file:///sdcard/uploads/a.png",
        ).forEach { assertFalse("$it", BrandParser.isAllowedLogoUrl(it)) }
    }

    @Test
    fun brandUrl() {
        assertEquals("https://wall.moonetix.com/eeee35416ef2e85e9b4d8203/brand.json", BrandParser.brandUrl("eeee35416ef2e85e9b4d8203"))
        assertNull(BrandParser.brandUrl("../evil"))
    }

    @Test
    fun sampleSize() {
        assertEquals(1, BrandParser.sampleSize(112, 112, 112))
        assertEquals(1, BrandParser.sampleSize(200, 200, 112))
        assertEquals(2, BrandParser.sampleSize(224, 224, 112))
        assertEquals(4, BrandParser.sampleSize(512, 512, 112))
        assertEquals(8, BrandParser.sampleSize(4000, 900, 112))
        assertEquals(1, BrandParser.sampleSize(0, 0, 112))
        assertEquals(1, BrandParser.sampleSize(50, 50, 112))
    }
}
