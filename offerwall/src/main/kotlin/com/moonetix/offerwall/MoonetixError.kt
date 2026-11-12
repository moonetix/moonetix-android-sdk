package com.moonetix.offerwall

/**
 * An error reported through [MoonetixListener.onError].
 *
 * @property code Machine-readable category of the error.
 * @property message Human-readable description for logs. It never contains the session token.
 */
data class MoonetixError(
    val code: Code,
    val message: String,
) {

    /** Error categories. */
    enum class Code {
        /** [Moonetix.open] was called before [Moonetix.init]. */
        NOT_INITIALIZED,

        /** The app ID passed to [Moonetix.init] is not a 24-character lowercase hex string. */
        INVALID_APP_ID,

        /** The token is malformed, or the server does not know it (HTTP 404). */
        INVALID_TOKEN,

        /** The token expired (10 minutes) or was already used (HTTP 410). Request a new one from your server. */
        TOKEN_EXPIRED_OR_USED,

        /** The offerwall could not be reached. The screen shows a retry button. */
        NETWORK,

        /** Anything else, for example no WebView available on the device. */
        UNKNOWN,
    }
}
