package com.moonetix.offerwall.internal

import java.net.URI
import java.util.Locale

/** What to do with a navigation. */
internal sealed class LinkAction {
    /** Load it in the offerwall WebView. */
    object StayInside : LinkAction()

    /** Open an http(s) URL in the user's browser (or a verified app link handler). */
    data class OpenBrowser(val url: String) : LinkAction()

    /** A play.google.com link: try the Play Store app first, then the browser. */
    data class OpenPlayStore(val url: String) : LinkAction()

    /** A `market:` URI for the Play Store app. */
    data class OpenMarket(val url: String) : LinkAction()

    /** An `intent:` URI, launched with sanitizing and fallbacks. */
    data class OpenIntent(val url: String) : LinkAction()

    /** `tel:`, `mailto:`, `sms:` or `smsto:`. */
    data class OpenContact(val url: String, val scheme: String) : LinkAction()

    /** Never loaded and never launched. */
    data class Block(val reason: String) : LinkAction()
}

/**
 * Decides where a URL is handled. Only `https://wall.moonetix.com/...` stays inside the WebView;
 * everything else leaves the app or is blocked. Pure Kotlin, no Android dependencies.
 */
internal class LinkPolicy(private val wallHosts: Set<String> = MoonetixUrls.WALL_HOSTS) {

    fun decide(rawUrl: String?): LinkAction {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return LinkAction.Block("empty")
        val scheme = schemeOf(url) ?: return LinkAction.Block("no scheme")
        return when (scheme) {
            "https", "http" -> decideWeb(url, scheme)
            "market" -> LinkAction.OpenMarket(url)
            "intent" -> LinkAction.OpenIntent(url)
            "tel", "mailto", "sms", "smsto" -> LinkAction.OpenContact(url, scheme)
            else -> LinkAction.Block("scheme $scheme")
        }
    }

    private fun decideWeb(url: String, scheme: String): LinkAction {
        val host = hostOf(url)
        if (scheme == "https" && isWallUrl(url)) return LinkAction.StayInside
        if (host != null && host in PLAY_HOSTS) return LinkAction.OpenPlayStore(url)
        return LinkAction.OpenBrowser(url)
    }

    /** True only for `https` URLs whose host is exactly a wall host, without userinfo and on the default port. */
    fun isWallUrl(rawUrl: String?): Boolean {
        val uri = parse(rawUrl) ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host ?: return false
        return host.lowercase(Locale.ROOT) in wallHosts
    }

    /** True for the token-exchange URL (a wall URL carrying a `token` query parameter). */
    fun isTokenUrl(rawUrl: String?): Boolean {
        if (!isWallUrl(rawUrl)) return false
        val query = parse(rawUrl)?.rawQuery ?: return false
        return query.split('&').any { it == "token" || it.startsWith("token=") }
    }

    /** True if [rawUrl] is http(s), so it is safe to use as an `intent:` browser fallback. */
    fun isWebUrl(rawUrl: String?): Boolean {
        val scheme = schemeOf(rawUrl?.trim().orEmpty())
        return scheme == "https" || scheme == "http"
    }

    companion object {
        private val SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val PLAY_HOSTS = setOf("play.google.com", "market.android.com")

        /** Lowercased scheme of [url], or null when it has none. */
        fun schemeOf(url: String): String? =
            SCHEME_REGEX.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        /** Lowercased host of [url], or null when it cannot be parsed strictly. */
        fun hostOf(url: String?): String? = parse(url)?.host?.lowercase(Locale.ROOT)

        fun isValidPackageName(name: String?): Boolean =
            name != null && name.length <= 255 && PACKAGE_REGEX.matches(name)

        /** `market://details?id=x` to the Play Store web page with the same query, or null. */
        fun marketToWebUrl(marketUrl: String): String? {
            val uri = parse(marketUrl) ?: return null
            if (!"market".equals(uri.scheme, ignoreCase = true)) return null
            val query = uri.rawQuery ?: return null
            if (!"details".equals(uri.host, ignoreCase = true)) return null
            return "https://play.google.com/store/apps/details?$query"
        }

        /** The Play Store `market://details` URI for [packageName], or null if the name is not valid. */
        fun marketUrlForPackage(packageName: String?): String? =
            if (isValidPackageName(packageName)) "market://details?id=$packageName" else null

        private fun parse(url: String?): URI? {
            if (url.isNullOrBlank()) return null
            return try {
                URI(url.trim())
            } catch (e: Exception) {
                null
            }
        }
    }
}
