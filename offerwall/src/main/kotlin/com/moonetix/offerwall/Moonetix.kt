package com.moonetix.offerwall

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moonetix.offerwall.internal.BrandLoader
import com.moonetix.offerwall.internal.MoonetixUrls
import java.util.UUID

/**
 * Entry point of the Moonetix Offerwall SDK.
 *
 * Typical use:
 * ```
 * Moonetix.init(applicationContext, "eeee35416ef2e85e9b4d8203")
 * Moonetix.setListener(myListener)
 * // token = fresh single-use session token fetched from YOUR server
 * Moonetix.open(activity, token)
 * ```
 *
 * Call all methods from the main thread.
 */
object Moonetix {

    /** Version of this SDK. Sent to the wall as `sdk=android-<VERSION>`. */
    const val VERSION = "2.0.1"

    // Application context only, so it cannot leak an Activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appId: String? = null

    @Volatile
    private var initRejected = false

    @Volatile
    private var listener: MoonetixListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Tokens waiting to be picked up by MoonetixActivity. They are kept in memory only, so the
    // token never travels through the system Intent (and never ends up in recents or on disk).
    private val pendingTokens = LinkedHashMap<String, String>()
    private const val MAX_PENDING = 8
    private const val TAG = "Moonetix"

    /**
     * Initializes the SDK. Call once, for example in `Application.onCreate()`.
     *
     * @param context Any context; only its application context is kept.
     * @param appId Your Moonetix app ID (24 lowercase hex characters, shown in the dashboard).
     * An invalid value is not stored; every following [open] call then reports
     * [MoonetixError.Code.INVALID_APP_ID] through [MoonetixListener.onError] instead of opening.
     */
    @JvmStatic
    fun init(context: Context, appId: String) {
        appContext = context.applicationContext ?: context
        val trimmed = appId.trim()
        if (MoonetixUrls.isValidAppId(trimmed)) {
            this.appId = trimmed
            initRejected = false
        } else {
            this.appId = null
            initRejected = true
            Log.w(TAG, INVALID_APP_ID_MESSAGE)
        }
    }

    /**
     * Opens the offerwall full screen.
     *
     * @param activity The Activity to launch from.
     * @param token A fresh session token (`sst_...`) that your server obtained from
     * `POST https://api.moonetix.com/v1/session`. Tokens are single-use and expire after 10 minutes,
     * so request a new one for every [open] call.
     * @param options Optional appearance settings. By default the top bar shows the Moonetix mark
     * with the title and colors from the Design tab of your dashboard.
     *
     * Problems (not initialized, malformed token, network, expired token) are reported through
     * [MoonetixListener.onError]; this method never throws for them.
     */
    @JvmStatic
    @JvmOverloads
    fun open(activity: Activity, token: String, options: MoonetixOptions = MoonetixOptions()) {
        val id = appId
        if (id == null) {
            if (initRejected) {
                notifyError(MoonetixError.Code.INVALID_APP_ID, INVALID_APP_ID_MESSAGE)
            } else {
                notifyError(MoonetixError.Code.NOT_INITIALIZED, "Call Moonetix.init(context, appId) before Moonetix.open().")
            }
            return
        }
        val cleanToken = token.trim()
        if (!MoonetixUrls.isValidToken(cleanToken)) {
            notifyError(MoonetixError.Code.INVALID_TOKEN, "The session token is malformed. Pass the \"token\" value returned by POST /v1/session.")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            notifyError(MoonetixError.Code.UNKNOWN, "The Activity passed to Moonetix.open() is finishing.")
            return
        }
        val launchId = UUID.randomUUID().toString()
        putPendingToken(launchId, cleanToken)
        val intent = Intent(activity, MoonetixActivity::class.java)
            .putExtra(MoonetixActivity.EXTRA_LAUNCH_ID, launchId)
            .putExtra(MoonetixActivity.EXTRA_APP_ID, id)
            .putExtra(MoonetixActivity.EXTRA_SHOW_LOGO, options.showLogo)
        options.title?.let { intent.putExtra(MoonetixActivity.EXTRA_TITLE, it) }
        options.toolbarColor?.let { intent.putExtra(MoonetixActivity.EXTRA_TOOLBAR_COLOR, it) }
        // Branding loads in parallel with the WebView and never delays the wall.
        BrandLoader.prefetch(id, MoonetixActivity.logoDecodePx(activity))
        try {
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            takePendingToken(launchId)
            notifyError(MoonetixError.Code.UNKNOWN, "Could not start the offerwall screen: ${e.javaClass.simpleName}.")
        }
    }

    /**
     * Sets the listener that receives offerwall events, or `null` to remove it.
     *
     * The listener is stored statically. Clear it (pass `null`) when the object it belongs to
     * is destroyed, for example in your Activity's `onDestroy()`.
     */
    @JvmStatic
    fun setListener(listener: MoonetixListener?) {
        this.listener = listener
    }

    private fun putPendingToken(launchId: String, token: String) {
        synchronized(pendingTokens) {
            while (pendingTokens.size >= MAX_PENDING) {
                pendingTokens.remove(pendingTokens.keys.first())
            }
            pendingTokens[launchId] = token
        }
    }

    /** Returns the token for [launchId] and forgets it, so it can be used only once. */
    internal fun takePendingToken(launchId: String?): String? {
        if (launchId == null) return null
        synchronized(pendingTokens) {
            return pendingTokens.remove(launchId)
        }
    }

    internal fun notifyError(code: MoonetixError.Code, message: String) {
        val error = MoonetixError(code, message)
        dispatch { it.onError(error) }
    }

    internal fun dispatch(event: (MoonetixListener) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.let(event)
        } else {
            mainHandler.post { listener?.let(event) }
        }
    }

    private const val INVALID_APP_ID_MESSAGE =
        "The app ID passed to Moonetix.init() is invalid. It must be the 24-character lowercase hex ID from your Moonetix dashboard."
}
