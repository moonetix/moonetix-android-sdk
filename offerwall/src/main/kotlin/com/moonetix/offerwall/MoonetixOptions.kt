package com.moonetix.offerwall

/**
 * Appearance options for the offerwall screen.
 *
 * By default the top bar shows the Moonetix mark with the title and colors from the Design tab
 * of your Moonetix dashboard, loaded automatically in the background. Set an option
 * here only to override it.
 *
 * @property title Text shown in the top bar. `null` (default) uses the title from your
 * Design tab, or "Earn rewards" when none is set.
 * @property toolbarColor Optional ARGB color for the top bar, for example `0xFF0B5FFF.toInt()`.
 * `null` (default) uses the background color from your Design tab, or a neutral light or dark
 * color that follows the system theme. The title and close icon always keep a readable contrast.
 * @property showLogo Whether to show the Moonetix mark next to the title.
 */
data class MoonetixOptions @JvmOverloads constructor(
    val title: String? = null,
    val toolbarColor: Int? = null,
    val showLogo: Boolean = true,
)
