package com.moonetix.offerwall.internal

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Publisher branding from `https://wall.moonetix.com/{APP_ID}/brand.json`. Colors are opaque ARGB. */
internal data class Brand(
    val title: String?,
    val logoUrl: String?,
    val background: Int?,
    val text: Int?,
    val primary: Int?,
)

/** Parsing and validation of the brand endpoint. Pure Kotlin plus org.json. */
internal object BrandParser {

    const val MAX_JSON_BYTES = 32 * 1024
    const val MAX_LOGO_BYTES = 512 * 1024
    private const val MAX_TITLE_LENGTH = 80

    internal val LOGO_HOSTS = setOf("moonetix.com") + MoonetixUrls.WALL_HOSTS
    private val HEX6 = Regex("^#[0-9A-Fa-f]{6}$")
    private val HEX3 = Regex("^#[0-9A-Fa-f]{3}$")

    /** `https://wall.moonetix.com/{appId}/brand.json`, or null for an invalid app ID. */
    fun brandUrl(appId: String): String? =
        if (MoonetixUrls.isValidAppId(appId)) "https://${MoonetixUrls.WALL_HOST}/$appId/brand.json" else null

    /** Parses a brand.json body. Returns null unless it is valid JSON with `"ok": true` and within the size cap. */
    fun parse(body: String?): Brand? {
        if (body == null || body.length > MAX_JSON_BYTES) return null
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        if (json.opt("ok") != true) return null
        val colors = json.opt("colors") as? JSONObject
        val logo = json.opt("logo_url") as? String
        return Brand(
            title = cleanTitle(json.opt("title") as? String),
            logoUrl = logo?.takeIf { isAllowedLogoUrl(it) },
            background = parseColor(colors?.opt("background") as? String),
            text = parseColor(colors?.opt("text") as? String),
            primary = parseColor(colors?.opt("primary") as? String),
        )
    }

    /** Trims, removes control characters and caps the length. Blank becomes null. */
    fun cleanTitle(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.filter { !Character.isISOControl(it) }.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_TITLE_LENGTH) cleaned.substring(0, MAX_TITLE_LENGTH).trim() else cleaned
    }

    /** `#RRGGBB` or `#RGB` to an opaque ARGB int, else null. */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        val hex = when {
            HEX6.matches(s) -> s.substring(1)
            HEX3.matches(s) -> s.substring(1).map { "$it$it" }.joinToString("")
            else -> return null
        }
        return (0xFF000000L or hex.toLong(16)).toInt()
    }

    /**
     * Only `/uploads/...` on moonetix.com or wall.moonetix.com, with no userinfo,
     * no custom port and no path traversal.
     */
    fun isAllowedLogoUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url != url.trim()) return false
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme != "https") return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host !in LOGO_HOSTS) return false
        val path = uri.rawPath ?: return false
        if (!path.startsWith("/uploads/") || path.length <= "/uploads/".length) return false
        if (path.contains("..") || path.contains("//") || path.lowercase(Locale.ROOT).contains("%2e") ||
            path.lowercase(Locale.ROOT).contains("%2f") || path.contains('\\')
        ) {
            return false
        }
        return true
    }

    /** Power-of-two sample size so the decoded image stays at least [targetPx] on its shorter side. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}
