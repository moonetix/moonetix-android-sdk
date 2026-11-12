package com.moonetix.offerwall.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.moonetix.offerwall.Moonetix
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** A loaded brand and its decoded logo (null when there is none or it failed). */
internal class BrandResult(val brand: Brand, val logo: Bitmap?, val loadedAt: Long)

/**
 * Fetches the publisher's brand.json and logo in the background and keeps them in memory per
 * app ID for 10 minutes. Every failure is silent: callers simply keep the default look.
 */
internal object BrandLoader {

    private const val CACHE_MS = 10 * 60 * 1000L
    private const val TIMEOUT_MS = 5_000
    private const val MAX_DIMENSION = 8192

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val executor: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "moonetix-brand").apply { isDaemon = true } }
    }

    private val lock = Any()
    private val cache = HashMap<String, BrandResult>()
    private val inFlight = HashMap<String, MutableList<(BrandResult) -> Unit>>()

    /** The cached brand for [appId] if it is younger than 10 minutes. */
    fun cached(appId: String): BrandResult? = synchronized(lock) {
        val hit = cache[appId] ?: return null
        if (SystemClock.elapsedRealtime() - hit.loadedAt > CACHE_MS) {
            cache.remove(appId)
            null
        } else {
            hit
        }
    }

    /** Starts loading unless a fresh copy is cached or a load is already running. */
    fun prefetch(appId: String, logoPx: Int) {
        load(appId, logoPx, null)
    }

    /**
     * Delivers the brand to [callback] on the main thread when it is loaded. Nothing is delivered
     * on failure. Returns the registered callback so it can be removed with [cancel].
     */
    fun request(appId: String, logoPx: Int, callback: (BrandResult) -> Unit): (BrandResult) -> Unit {
        load(appId, logoPx, callback)
        return callback
    }

    /** Stops [callback] from being called (for example when the Activity is destroyed). */
    fun cancel(appId: String, callback: (BrandResult) -> Unit) {
        synchronized(lock) { inFlight[appId]?.remove(callback) }
    }

    private fun load(appId: String, logoPx: Int, callback: ((BrandResult) -> Unit)?) {
        val url = BrandParser.brandUrl(appId) ?: return
        cached(appId)?.let { hit ->
            callback?.let { cb -> mainHandler.post { cb(hit) } }
            return
        }
        synchronized(lock) {
            val waiting = inFlight[appId]
            if (waiting != null) {
                callback?.let { waiting.add(it) }
                return
            }
            inFlight[appId] = ArrayList<(BrandResult) -> Unit>().also { list -> callback?.let { list.add(it) } }
        }
        try {
            executor.execute { fetch(appId, url, logoPx) }
        } catch (e: RuntimeException) {
            synchronized(lock) { inFlight.remove(appId) }
        }
    }

    private fun fetch(appId: String, url: String, logoPx: Int) {
        var result: BrandResult? = null
        try {
            val body = httpGet(url, BrandParser.MAX_JSON_BYTES, "application/json")
            val brand = body?.let { BrandParser.parse(String(it, Charsets.UTF_8)) }
            if (brand != null) {
                val logo = brand.logoUrl?.let { decodeLogo(it, logoPx) }
                result = BrandResult(brand, logo, SystemClock.elapsedRealtime())
            }
        } catch (e: Throwable) {
            // OutOfMemoryError included: branding is optional, keep the default look.
            result = null
        }
        val callbacks = synchronized(lock) {
            if (result != null) cache[appId] = result
            inFlight.remove(appId)?.toList().orEmpty()
        }
        val loaded = result ?: return
        if (callbacks.isEmpty()) return
        mainHandler.post { for (cb in callbacks) cb(loaded) }
    }

    private fun decodeLogo(url: String, targetPx: Int): Bitmap? {
        if (!BrandParser.isAllowedLogoUrl(url)) return null
        val bytes = httpGet(url, BrandParser.MAX_LOGO_BYTES, "image/webp,image/png,image/jpeg,image/gif") ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) return null
        val options = BitmapFactory.Options().apply { inSampleSize = BrandParser.sampleSize(w, h, targetPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** GET over HTTPS only, no redirects, 5 s timeouts, body capped at [maxBytes]. Null on any problem. */
    private fun httpGet(url: String, maxBytes: Int, accept: String): ByteArray? {
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: return null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "MoonetixSDK/" + Moonetix.VERSION + " (Android)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declared = connection.contentLength
            if (declared > maxBytes) return null
            return connection.inputStream.use { readCapped(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
