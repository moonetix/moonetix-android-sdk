package com.moonetix.offerwall.internal

/** WCAG color math on ARGB ints. Pure Kotlin. */
internal object ColorMath {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun opaque(color: Int): Int = color or BLACK

    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever contrasts more with [background] (always at least 4.58:1). */
    fun readableOn(background: Int): Int =
        if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK

    /** True when dark content (icons, text) belongs on [background]. */
    fun isLight(background: Int): Boolean = contrast(BLACK, background) > contrast(WHITE, background)
}

/** Resolved look of the top bar. */
internal data class BarStyle(
    val title: String,
    val barColor: Int,
    val onBarColor: Int,
    val progressColor: Int,
    val isDefaultBar: Boolean,
) {
    val lightBar: Boolean get() = ColorMath.isLight(barColor)
}

/** Precedence: explicit options, then the publisher's brand, then the SDK defaults. Pure Kotlin. */
internal object BarStyleResolver {

    const val DEFAULT_TITLE = "Earn rewards"
    const val MIN_TEXT_CONTRAST = 4.5
    const val MIN_PROGRESS_CONTRAST = 3.0

    fun resolve(
        optionTitle: String?,
        optionColor: Int?,
        brand: Brand?,
        defaultBar: Int,
        defaultOnBar: Int,
        defaultAccent: Int,
    ): BarStyle {
        val title = BrandParser.cleanTitle(optionTitle) ?: brand?.title ?: DEFAULT_TITLE

        val brandBackground = brand?.background
        val bar: Int
        val preferredText: Int?
        val isDefault: Boolean
        when {
            optionColor != null -> {
                bar = ColorMath.opaque(optionColor)
                preferredText = null
                isDefault = false
            }
            brandBackground != null -> {
                bar = ColorMath.opaque(brandBackground)
                preferredText = brand?.text
                isDefault = false
            }
            else -> {
                bar = ColorMath.opaque(defaultBar)
                preferredText = defaultOnBar
                isDefault = true
            }
        }

        val onBar = preferredText
            ?.let { ColorMath.opaque(it) }
            ?.takeIf { ColorMath.contrast(it, bar) >= MIN_TEXT_CONTRAST }
            ?: ColorMath.readableOn(bar)

        val accent = ColorMath.opaque(brand?.primary ?: defaultAccent)
        val progress = if (ColorMath.contrast(accent, bar) >= MIN_PROGRESS_CONTRAST) accent else onBar

        return BarStyle(title, bar, onBar, progress, isDefault)
    }
}
