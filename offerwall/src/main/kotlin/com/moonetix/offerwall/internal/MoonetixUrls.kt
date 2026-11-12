package com.moonetix.offerwall.internal

/** Input validation and wall URL building. Pure Kotlin, no Android dependencies. */
internal object MoonetixUrls {

    const val WALL_HOST = "wall.moonetix.com"

    val WALL_HOSTS: Set<String> = setOf(WALL_HOST)

    private val APP_ID_REGEX = Regex("^[a-f0-9]{24}$")
    private val TOKEN_REGEX = Regex("^sst_[A-Za-z0-9_-]{43}$")

    fun isValidAppId(appId: String?): Boolean = appId != null && APP_ID_REGEX.matches(appId)

    fun isValidToken(token: String?): Boolean = token != null && TOKEN_REGEX.matches(token)

    /**
     * Builds `https://wall.moonetix.com/{appId}?token={token}&sdk=android-{version}`.
     * @throws IllegalArgumentException if [appId] or [token] is invalid.
     */
    fun buildWallUrl(appId: String, token: String, sdkVersion: String): String {
        require(isValidAppId(appId)) { "Invalid app ID" }
        require(isValidToken(token)) { "Invalid token" }
        return "https://$WALL_HOST/$appId?token=${encodeQueryValue(token)}&sdk=${encodeQueryValue("android-$sdkVersion")}"
    }

    /** Percent-encodes [value] for a URL query, keeping only RFC 3986 unreserved characters. */
    fun encodeQueryValue(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
