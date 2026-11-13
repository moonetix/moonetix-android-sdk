package com.moonetix.offerwall.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarStyleTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val defaultBar = white
    private val defaultOnBar = 0xFF111827.toInt()
    private val defaultAccent = 0xFF2563EB.toInt()

    private val sampleBrand = Brand("Example Rewards", null, black, white, 0xFF2D69FA.toInt())

    private fun resolve(title: String? = null, color: Int? = null, brand: Brand? = null) =
        BarStyleResolver.resolve(title, color, brand, defaultBar, defaultOnBar, defaultAccent)

    @Test
    fun contrastMath() {
        assertEquals(21.0, ColorMath.contrast(black, white), 0.01)
        assertEquals(1.0, ColorMath.contrast(0xFF2D69FA.toInt(), 0xFF2D69FA.toInt()), 0.0001)
        assertEquals(ColorMath.contrast(black, white), ColorMath.contrast(white, black), 0.0)
        assertEquals(5.17, ColorMath.contrast(defaultAccent, white), 0.01)
        assertTrue(ColorMath.isLight(white))
        assertFalse(ColorMath.isLight(black))
        assertEquals(black, ColorMath.readableOn(white))
        assertEquals(white, ColorMath.readableOn(black))
        assertEquals(black, ColorMath.opaque(0x00000000))
    }

    @Test
    fun readableOnAlwaysMeetsAa() {
        var c = 0
        while (c <= 0xFFFFFF) {
            val bg = ColorMath.opaque(c)
            assertTrue(ColorMath.contrast(ColorMath.readableOn(bg), bg) >= 4.5)
            c += 0x010305
        }
    }

    @Test
    fun noBrandUsesDefaults() {
        val s = resolve()
        assertEquals("Earn rewards", s.title)
        assertEquals(defaultBar, s.barColor)
        assertEquals(defaultOnBar, s.onBarColor)
        assertEquals(defaultAccent, s.progressColor)
        assertTrue(s.isDefaultBar)
        assertTrue(s.lightBar)
    }

    @Test
    fun brandIsUsedWhenNoOptions() {
        val s = resolve(brand = sampleBrand)
        assertEquals("Example Rewards", s.title)
        assertEquals(black, s.barColor)
        assertEquals(white, s.onBarColor)
        assertEquals(0xFF2D69FA.toInt(), s.progressColor) // 4.2:1 on black
        assertFalse(s.isDefaultBar)
        assertFalse(s.lightBar)
    }

    @Test
    fun explicitOptionsWinOverBrand() {
        val s = resolve(title = "Earn coins", color = 0xFFFFEB3B.toInt(), brand = sampleBrand)
        assertEquals("Earn coins", s.title)
        assertEquals(0xFFFFEB3B.toInt(), s.barColor)
        // Brand text color is only used with the brand background; auto contrast otherwise.
        assertEquals(black, s.onBarColor)
        assertTrue(s.lightBar)
    }

    @Test
    fun blankOptionTitleFallsBackToBrand() {
        assertEquals("Example Rewards", resolve(title = "  ", brand = sampleBrand).title)
    }

    @Test
    fun brandTitleWithoutColors() {
        val s = resolve(brand = Brand("Only title", null, null, null, null))
        assertEquals("Only title", s.title)
        assertEquals(defaultBar, s.barColor)
        assertTrue(s.isDefaultBar)
    }

    @Test
    fun failingBrandTextFallsBackToBlackOrWhite() {
        // Gray text on gray background fails 4.5:1.
        val s = resolve(brand = Brand("X", null, 0xFF777777.toInt(), 0xFF888888.toInt(), null))
        assertTrue(ColorMath.contrast(s.onBarColor, s.barColor) >= 4.5)
        assertTrue(s.onBarColor == black || s.onBarColor == white)
    }

    @Test
    fun missingBrandTextUsesAutoContrast() {
        val s = resolve(brand = Brand("X", null, 0xFF0B5FFF.toInt(), null, null))
        assertEquals(white, s.onBarColor)
    }

    @Test
    fun lowContrastPrimaryFallsBackToTextColor() {
        // Blue progress on a nearly identical blue bar is under 3:1.
        val s = resolve(brand = Brand("X", null, 0xFF2D69FA.toInt(), white, 0xFF3070FF.toInt()))
        assertEquals(s.onBarColor, s.progressColor)
    }

    @Test
    fun defaultAccentIsCheckedAgainstCustomBar() {
        val s = resolve(color = 0xFF2563EB.toInt())
        assertEquals(s.onBarColor, s.progressColor)
    }

    @Test
    fun translucentColorsAreMadeOpaque() {
        val s = resolve(color = 0x80FF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), s.barColor)
    }

    @Test
    fun statusBarIconsFollowBarLuminance() {
        assertTrue(resolve(color = 0xFFFFEB3B.toInt()).lightBar)
        assertFalse(resolve(color = 0xFF1A237E.toInt()).lightBar)
        assertFalse(resolve(brand = sampleBrand).lightBar)
    }
}
