package com.moonetix.offerwall

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.moonetix.offerwall.internal.BarStyleResolver
import com.moonetix.offerwall.internal.Brand
import com.moonetix.offerwall.internal.BrandLoader
import com.moonetix.offerwall.internal.BrandResult
import com.moonetix.offerwall.internal.ColorMath
import com.moonetix.offerwall.internal.ExternalLauncher
import com.moonetix.offerwall.internal.LinkPolicy
import com.moonetix.offerwall.internal.MoonetixUrls
import kotlin.math.max

/**
 * Full-screen offerwall screen. Started by [Moonetix.open]; do not start it directly.
 *
 * The single-use session token is loaded at most once. Rotation and other configuration
 * changes do not recreate the Activity, and a recreated Activity restores the WebView state
 * (the already redirected wall URL) instead of loading the token again.
 */
class MoonetixActivity : Activity() {

    private val policy = LinkPolicy()
    private lateinit var launcher: ExternalLauncher
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private val popups = ArrayList<WebView>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var backCallback: Any? = null

    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var divider: View
    private lateinit var titleView: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button

    private lateinit var logoView: ImageView

    private var toolbarColor: Int? = null
    private var optionTitle: String? = null
    private var showLogo = true
    private var appId: String? = null
    private var brand: Brand? = null
    private var brandCallback: ((BrandResult) -> Unit)? = null

    // Kept in memory only, never logged, never saved to the instance state.
    private var tokenUrl: String? = null
    private var tokenRequestSent = false
    private var currentWallUrl: String? = null
    private var failedUrl: String? = null

    private var errorShowing = false
    private var openedReported = false
    private var tokenErrorReported = false
    private var closedReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = ExternalLauncher(this, policy) { url -> loadWallUrl(url) }

        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val launchId = intent.getStringExtra(EXTRA_LAUNCH_ID)
        toolbarColor = if (intent.hasExtra(EXTRA_TOOLBAR_COLOR)) intent.getIntExtra(EXTRA_TOOLBAR_COLOR, 0) else null
        optionTitle = intent.getStringExtra(EXTRA_TITLE)
        showLogo = intent.getBooleanExtra(EXTRA_SHOW_LOGO, true)

        buildUi()
        applyColors()
        registerBackCallback()

        val web = createWebView()
        if (web == null) {
            Moonetix.notifyError(MoonetixError.Code.UNKNOWN, "Android System WebView is not available on this device.")
            finish()
            return
        }
        webView = web

        if (appId == null || !MoonetixUrls.isValidAppId(appId)) {
            Moonetix.takePendingToken(launchId)
            Moonetix.notifyError(MoonetixError.Code.INVALID_APP_ID, "The offerwall was started without a valid app ID.")
            finish()
            return
        }
        this.appId = appId
        loadBrand(appId)

        if (savedInstanceState != null) {
            tokenRequestSent = savedInstanceState.getBoolean(STATE_TOKEN_SENT, false)
            currentWallUrl = savedInstanceState.getString(STATE_WALL_URL)
            openedReported = savedInstanceState.getBoolean(STATE_OPENED, false)
            tokenErrorReported = savedInstanceState.getBoolean(STATE_TOKEN_ERROR, false)
            val webState = savedInstanceState.getBundle(STATE_WEBVIEW)
            if (webState != null && web.restoreState(webState) != null) return
            val wallUrl = currentWallUrl
            if (wallUrl != null) {
                web.loadUrl(wallUrl)
                return
            }
            if (tokenRequestSent) {
                showSessionEnded()
                return
            }
        }

        val token = Moonetix.takePendingToken(launchId)
        if (token == null) {
            // The token was already consumed by an earlier instance (or the process was restarted).
            showSessionEnded()
            return
        }
        tokenUrl = MoonetixUrls.buildWallUrl(appId, token, Moonetix.VERSION)
        loadToken()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(4), 0, dp(16), 0)
        }
        closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.moonetix_ic_close)
            contentDescription = getString(R.string.moonetix_close)
            scaleType = ImageView.ScaleType.CENTER
            background = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            setOnClickListener { finish() }
        }
        row.addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        logoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
            }
            clipToOutline = true
        }
        row.addView(logoView, LinearLayout.LayoutParams(dp(LOGO_DP), dp(LOGO_DP)).apply { marginStart = dp(4) })
        titleView = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(dp(12), 0, 0, 0)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        divider = View(this)
        bar.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
        }
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            isClickable = true
        }
        errorTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        errorMessage = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        retryButton = Button(this).apply {
            text = getString(R.string.moonetix_retry)
            minHeight = dp(48)
            setOnClickListener { retry() }
        }
        errorView.addView(errorTitle)
        errorView.addView(errorMessage)
        errorView.addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        content.addView(progressBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        content.addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets) }
        setContentView(root)
    }

    private fun applyInsets(insets: WindowInsets): WindowInsets {
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = max(bars.bottom, ime.bottom)
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        bar.setPadding(left, top, right, 0)
        (root.getChildAt(1) as? View)?.setPadding(left, 0, right, bottom)
        return if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets.CONSUMED
        } else {
            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }
    }

    private fun applyColors() {
        val background = color(R.color.moonetix_background)
        val accent = color(R.color.moonetix_accent)
        val style = BarStyleResolver.resolve(
            optionTitle = optionTitle,
            optionColor = toolbarColor,
            brand = brand,
            defaultBar = color(R.color.moonetix_bar),
            defaultOnBar = color(R.color.moonetix_on_bar),
            defaultAccent = accent,
        )
        val barColor = style.barColor
        val onBar = style.onBarColor

        root.setBackgroundColor(background)
        window.setBackgroundDrawable(ColorDrawable(background))
        bar.setBackgroundColor(barColor)
        divider.setBackgroundColor(color(R.color.moonetix_divider))
        divider.visibility = if (style.isDefaultBar) View.VISIBLE else View.GONE
        titleView.text = style.title
        titleView.setTextColor(onBar)
        progressBar.contentDescription = style.title
        closeButton.imageTintList = ColorStateList.valueOf(onBar)
        progressBar.progressDrawable = ClipDrawable(ColorDrawable(style.progressColor), Gravity.START, ClipDrawable.HORIZONTAL)
        progressBar.background = null
        errorView.setBackgroundColor(background)
        errorTitle.setTextColor(color(R.color.moonetix_on_bar))
        errorMessage.setTextColor(color(R.color.moonetix_text_secondary))
        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
        retryButton.setTextColor(color(R.color.moonetix_on_accent))
        webView?.setBackgroundColor(background)

        applySystemBars(barColor, style.lightBar, background, ColorMath.isLight(background))
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(barColor: Int, lightBar: Boolean, background: Int, lightBackground: Boolean) {
        if (Build.VERSION.SDK_INT < 35) {
            // From API 35 the window is edge-to-edge: the top bar draws behind the status bar instead.
            window.statusBarColor = if (lightBar && Build.VERSION.SDK_INT < 23) darken(barColor) else barColor
            if (Build.VERSION.SDK_INT >= 26) window.navigationBarColor = background
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                (if (lightBar) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                    (if (lightBackground) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0),
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else if (Build.VERSION.SDK_INT >= 23) {
            var flags = window.decorView.systemUiVisibility
            flags = if (lightBar) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) {
                flags = if (lightBackground) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is handled here so light/dark switches do not recreate the Activity (and reload the token).
        applyColors()
    }

    // ---------------------------------------------------------------- Branding

    private fun loadBrand(appId: String) {
        val cached = BrandLoader.cached(appId)
        if (cached != null) {
            applyBrand(cached, animate = false)
            return
        }
        val callback: (BrandResult) -> Unit = { result ->
            brandCallback = null
            if (!isFinishing && !isDestroyed) applyBrand(result, animate = true)
        }
        brandCallback = callback
        BrandLoader.request(appId, logoDecodePx(this), callback)
    }

    private fun applyBrand(result: BrandResult, animate: Boolean) {
        brand = result.brand
        applyColors()
        val logo = result.logo
        if (!showLogo || logo == null) return
        logoView.setImageBitmap(logo)
        logoView.visibility = View.VISIBLE
        if (animate) {
            logoView.alpha = 0f
            logoView.animate().alpha(1f).setDuration(LOGO_FADE_MS).start()
        } else {
            logoView.alpha = 1f
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView? {
        val web = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // Missing or updating WebView provider.
            return null
        }
        configureSettings(web.settings)
        web.settings.userAgentString = (web.settings.userAgentString ?: "") + " MoonetixSDK/" + Moonetix.VERSION
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        web.setBackgroundColor(color(R.color.moonetix_background))
        web.webViewClient = MainClient()
        web.webChromeClient = MainChromeClient()
        val content = root.getChildAt(1) as FrameLayout
        content.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return web
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        s.setGeolocationEnabled(false)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = true
    }

    private fun loadToken() {
        val url = tokenUrl ?: return
        hideError()
        tokenRequestSent = true
        webView?.loadUrl(url)
    }

    private fun loadWallUrl(url: String) {
        hideError()
        webView?.loadUrl(url)
    }

    private fun retry() {
        val wallUrl = currentWallUrl
        when {
            wallUrl != null -> loadWallUrl(wallUrl)
            tokenUrl != null && !tokenRequestSent -> loadToken()
            else -> showSessionEnded()
        }
    }

    private fun rememberWallUrl(url: String?) {
        if (url != null && policy.isWallUrl(url) && !policy.isTokenUrl(url)) currentWallUrl = url
    }

    private fun handleMainFrameError(url: String?, errorCode: Int) {
        if (url == null || !policy.isWallUrl(url)) return
        if (errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return
        if (policy.isTokenUrl(url)) {
            // The token request never reached the server for these errors, so loading it again is safe.
            if (errorCode in UNSENT_ERRORS) tokenRequestSent = false
        } else {
            currentWallUrl = url
        }
        val alreadyShowing = errorShowing && failedUrl == url
        failedUrl = url
        val canRetry = currentWallUrl != null || (tokenUrl != null && !tokenRequestSent)
        if (canRetry) {
            showError(R.string.moonetix_error_network_title, R.string.moonetix_error_network_message, retryable = true)
        } else {
            showError(R.string.moonetix_error_session_title, R.string.moonetix_error_session_message, retryable = false)
        }
        if (!alreadyShowing) {
            Moonetix.notifyError(MoonetixError.Code.NETWORK, "The offerwall could not be loaded (WebView error $errorCode).")
        }
    }

    private fun reportTokenError(code: MoonetixError.Code, message: String) {
        if (tokenErrorReported) return
        tokenErrorReported = true
        Moonetix.notifyError(code, message)
    }

    private fun showSessionEnded() {
        showError(R.string.moonetix_error_session_title, R.string.moonetix_error_session_message, retryable = false)
        reportTokenError(
            MoonetixError.Code.TOKEN_EXPIRED_OR_USED,
            "The session token was already used and the offerwall cannot be restored. Request a new token and open it again.",
        )
    }

    private fun showError(titleRes: Int, messageRes: Int, retryable: Boolean) {
        errorShowing = true
        errorTitle.setText(titleRes)
        errorMessage.setText(messageRes)
        retryButton.visibility = if (retryable) View.VISIBLE else View.GONE
        errorView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun hideError() {
        errorShowing = false
        failedUrl = null
        errorView.visibility = View.GONE
    }

    private inner class MainClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()
            if (!request.isForMainFrame) {
                // Subframes only reach this for non-http(s) schemes: never launch anything from them.
                return !policy.isWebUrl(url)
            }
            return launcher.handle(url)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = launcher.handle(url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            rememberWallUrl(url)
            if (!errorShowing) progressBar.visibility = View.VISIBLE
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            rememberWallUrl(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progressBar.visibility = View.GONE
            rememberWallUrl(url)
            if (errorShowing || url == null || url == failedUrl) return
            if (!openedReported && policy.isWallUrl(url) && !policy.isTokenUrl(url)) {
                openedReported = true
                Moonetix.dispatch { it.onOpened() }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val url = request.url?.toString()
            if (!policy.isTokenUrl(url)) return
            when (errorResponse.statusCode) {
                404 -> reportTokenError(MoonetixError.Code.INVALID_TOKEN, "The session token is unknown or belongs to another app (HTTP 404).")
                410 -> reportTokenError(MoonetixError.Code.TOKEN_EXPIRED_OR_USED, "The session token expired or was already used (HTTP 410). Request a new token.")
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Only called on API 23+; the explicit check keeps lint's NewApi analysis precise.
            if (Build.VERSION.SDK_INT >= 23 && request.isForMainFrame) {
                handleMainFrameError(request.url?.toString(), error.errorCode)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < 23) handleMainFrameError(failingUrl, errorCode)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (policy.isWallUrl(error.url)) handleMainFrameError(error.url, ERROR_FAILED_SSL_HANDSHAKE)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                destroyWebView()
                showError(R.string.moonetix_error_crash_title, R.string.moonetix_error_crash_message, retryable = false)
                Moonetix.notifyError(MoonetixError.Code.UNKNOWN, "The WebView renderer stopped unexpectedly.")
            } else {
                closePopup(view)
            }
            return true
        }
    }

    private inner class MainChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            if (newProgress >= 100 || errorShowing) progressBar.visibility = View.GONE
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = try {
                WebView(this@MoonetixActivity)
            } catch (e: RuntimeException) {
                return false
            }
            configureSettings(popup.settings)
            popup.settings.setSupportMultipleWindows(false)
            popup.settings.userAgentString = webView?.settings?.userAgentString
            popup.webViewClient = PopupClient(popup)
            popups.add(popup)
            transport.webView = popup
            resultMsg.sendToTarget()
            mainHandler.postDelayed({ closePopup(popup) }, POPUP_TIMEOUT_MS)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE)
            } catch (e: RuntimeException) {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
            }
            return true
        }
    }

    /**
     * Captures the URL of a `target=_blank` / `window.open` navigation and routes it through the
     * same link policy. Wall pages are moved into the main WebView; everything else leaves the app.
     */
    private inner class PopupClient(private val popup: WebView) : WebViewClient() {
        private var done = false

        private fun route(url: String?): Boolean {
            if (done) return true
            if (url == null || url == "about:blank") return false
            if (policy.isWallUrl(url)) return false
            done = true
            launcher.handle(url)
            closePopup(popup)
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()
            if (!request.isForMainFrame) return !policy.isWebUrl(url)
            return route(url)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = route(url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!done && route(url)) view.stopLoading()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (done || url == null || !policy.isWallUrl(url)) return
            done = true
            loadWallUrl(url)
            closePopup(popup)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                done = true
                closePopup(popup)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }
    }

    private fun closePopup(popup: WebView) {
        if (!popups.remove(popup)) return
        // Destroy outside of the popup's own callback.
        mainHandler.post {
            popup.stopLoading()
            popup.destroy()
        }
    }

    // ---------------------------------------------------------------- Lifecycle

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // API < 33, or API 33 hosts that have not opted in to OnBackInvokedCallback app-wide.
        handleBack()
    }

    private fun handleBack() {
        val web = webView
        if (web != null && web.canGoBack()) {
            hideError()
            web.goBack()
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        if (isFinishing) reportClosed()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { web ->
            val state = Bundle()
            if (web.saveState(state) != null) outState.putBundle(STATE_WEBVIEW, state)
        }
        outState.putBoolean(STATE_TOKEN_SENT, tokenRequestSent || tokenUrl == null)
        outState.putString(STATE_WALL_URL, currentWallUrl)
        outState.putBoolean(STATE_OPENED, openedReported)
        outState.putBoolean(STATE_TOKEN_ERROR, tokenErrorReported)
    }

    override fun onDestroy() {
        if (isFinishing) reportClosed()
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        val pendingBrand = brandCallback
        val id = appId
        if (pendingBrand != null && id != null) BrandLoader.cancel(id, pendingBrand)
        brandCallback = null
        logoView.animate().cancel()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        for (popup in ArrayList(popups)) {
            popup.stopLoading()
            popup.destroy()
        }
        popups.clear()
        destroyWebView()
        tokenUrl = null
        super.onDestroy()
    }

    private fun destroyWebView() {
        val web = webView ?: return
        webView = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.webChromeClient = null
        web.webViewClient = WebViewClient()
        web.removeAllViews()
        web.destroy()
    }

    private fun reportClosed() {
        if (closedReported) return
        closedReported = true
        Moonetix.dispatch { it.onClosed() }
    }

    // ---------------------------------------------------------------- Helpers

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = if (Build.VERSION.SDK_INT >= 23) getColor(id) else resources.getColor(id)

    private fun themeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true) || value.resourceId == 0) return null
        return getDrawable(value.resourceId)
    }

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.8).toInt(),
        (Color.green(color) * 0.8).toInt(),
        (Color.blue(color) * 0.8).toInt(),
    )

    internal companion object {
        internal const val EXTRA_LAUNCH_ID = "com.moonetix.offerwall.LAUNCH_ID"
        internal const val EXTRA_APP_ID = "com.moonetix.offerwall.APP_ID"
        internal const val EXTRA_TITLE = "com.moonetix.offerwall.TITLE"
        internal const val EXTRA_TOOLBAR_COLOR = "com.moonetix.offerwall.TOOLBAR_COLOR"
        internal const val EXTRA_SHOW_LOGO = "com.moonetix.offerwall.SHOW_LOGO"

        private const val LOGO_DP = 28
        private const val LOGO_FADE_MS = 150L

        /** Pixel size the logo is decoded at: about twice its 28dp display size. */
        internal fun logoDecodePx(context: android.content.Context): Int =
            (LOGO_DP * 2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(LOGO_DP * 2)

        private const val STATE_WEBVIEW = "moonetix:webview"
        private const val STATE_TOKEN_SENT = "moonetix:token_sent"
        private const val STATE_WALL_URL = "moonetix:wall_url"
        private const val STATE_OPENED = "moonetix:opened"
        private const val STATE_TOKEN_ERROR = "moonetix:token_error"

        private const val REQUEST_FILE = 0x5A1
        private const val POPUP_TIMEOUT_MS = 60_000L

        private val UNSENT_ERRORS = setOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_PROXY_AUTHENTICATION,
        )
    }
}
