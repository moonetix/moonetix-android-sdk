package com.moonetix.offerwall.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Launches links that leave the offerwall. Every launch is guarded; failures fall back or are ignored. */
internal class ExternalLauncher(
    private val activity: Activity,
    private val policy: LinkPolicy,
    private val loadInWall: (String) -> Unit,
) {

    /** Handles [url] according to [LinkPolicy]. Returns true if the WebView must not load it. */
    fun handle(url: String?): Boolean {
        return when (val action = policy.decide(url)) {
            LinkAction.StayInside -> false
            is LinkAction.OpenBrowser -> {
                openBrowser(action.url)
                true
            }
            is LinkAction.OpenPlayStore -> {
                if (!start(viewIntent(action.url).setPackage(PLAY_STORE_PACKAGE))) openBrowser(action.url)
                true
            }
            is LinkAction.OpenMarket -> {
                openMarket(action.url)
                true
            }
            is LinkAction.OpenIntent -> {
                openIntentUri(action.url)
                true
            }
            is LinkAction.OpenContact -> {
                val uri = Uri.parse(action.url)
                val intent = if (action.scheme == "tel") Intent(Intent.ACTION_VIEW, uri) else Intent(Intent.ACTION_SENDTO, uri)
                start(intent)
                true
            }
            is LinkAction.Block -> true
        }
    }

    private fun openBrowser(url: String): Boolean = start(viewIntent(url))

    private fun openMarket(url: String) {
        if (start(viewIntent(url))) return
        LinkPolicy.marketToWebUrl(url)?.let { openBrowser(it) }
    }

    private fun openIntentUri(url: String) {
        val parsed: Intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        val intent = sanitize(parsed)
        if (start(intent)) return

        val fallback = parsed.getStringExtraSafe(BROWSER_FALLBACK_URL)
        if (fallback != null && policy.isWebUrl(fallback)) {
            if (policy.isWallUrl(fallback)) {
                loadInWall(fallback)
                return
            }
            if (openBrowser(fallback)) return
        }
        val market = LinkPolicy.marketUrlForPackage(parsed.`package`)
        if (market != null) openMarket(market)
    }

    private fun sanitize(source: Intent): Intent {
        val intent = Intent(source)
        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.flags = intent.flags and URI_PERMISSION_FLAGS.inv()
        return intent
    }

    private fun viewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun start(intent: Intent): Boolean {
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun Intent.getStringExtraSafe(name: String): String? = try {
        getStringExtra(name)
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val BROWSER_FALLBACK_URL = "browser_fallback_url"
        const val URI_PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
