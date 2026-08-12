package com.multiassist.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.URLUtilCompat
import org.woheller69.freeDroidWarn.FreeDroidWarn
import java.util.EnumMap

class MainActivity : ComponentActivity() {

    private val webViews: MutableMap<Provider, WebView> = EnumMap(Provider::class.java)
    private var currentProvider = Provider.CHATGPT

    private lateinit var webViewContainer: FrameLayout
    private lateinit var providerTabs: LinearLayout
    private val tabButtons: MutableMap<Provider, ImageButton> = EnumMap(Provider::class.java)
    private lateinit var cookieManager: CookieManager

    private val context: Context = this
    private var restricted = true

    private var mUploadMessage: ValueCallback<Array<Uri>>? = null

    // Activity Result API for file choosing
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val dataString = result.data?.dataString
            if (dataString != null) {
                results = arrayOf(Uri.parse(dataString))
            }
        }
        mUploadMessage?.onReceiveValue(results)
        mUploadMessage = null
    }

    // Activity Result API for Microphone Permission
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Microphone permission granted.", Toast.LENGTH_SHORT).show()
            // We can't automatically grant the pending WebView request easily from here, 
            // so the user will just need to tap the mic button again.
        } else {
            Toast.makeText(context, "Microphone permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "multiAssist"
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onPause() {
        if (::cookieManager.isInitialized) cookieManager.flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        // Apply theme before view inflation
        val themeOverride = prefs.getString("theme_override", "SYSTEM")
        when (themeOverride) {
            "LIGHT" -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            "DARK" -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            else -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        restricted = prefs.getBoolean("restricted_mode", true)
        
        val defaultTabKey = prefs.getString("default_tab", "CHATGPT")
        val defaultTab = Provider.values().find { it.name == defaultTabKey } ?: Provider.CHATGPT
        
        val allProviders = Provider.values().map { it.name }.toSet()
        val enabledKeys = prefs.getStringSet("enabled_providers", allProviders) ?: allProviders
        val enabledProviders = Provider.values().filter { enabledKeys.contains(it.name) }.ifEmpty { listOf(Provider.CHATGPT) }

        webViewContainer = findViewById(R.id.webview_container)
        providerTabs = findViewById(R.id.provider_tabs)

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // Build tab strip
        for (p in enabledProviders) {
            val btn = ImageButton(this).apply {
                setImageResource(p.iconRes)
                background = null
                contentDescription = p.label
                setColorFilter(0x80FFFFFF.toInt(), PorterDuff.Mode.SRC_ATOP)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { switchProvider(p) }
            }
            tabButtons[p] = btn
            providerTabs.addView(btn)
        }

        // Pre-load all enabled providers
        for (p in enabledProviders) {
            val wv = createWebViewForProvider(p)
            wv.visibility = View.GONE
            webViews[p] = wv
            webViewContainer.addView(
                wv,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            wv.loadUrl(p.url)
        }

        // Show default provider (if it's not enabled, fallback to first enabled)
        val initialProvider = if (enabledProviders.contains(defaultTab)) defaultTab else enabledProviders.first()
        switchProvider(initialProvider)
        
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE)
    }

    // ─── Provider switching ───────────────────────────────────────────────────

    private fun switchProvider(provider: Provider) {
        webViews.values.forEach { it.visibility = View.GONE }

        val active = webViews[provider]
        active?.visibility = View.VISIBLE
        currentProvider = provider

        active?.evaluateJavascript(
            "setTimeout(function() { " +
                    "var input = document.querySelector('textarea, [contenteditable=\"true\"]'); " +
                    "if (input) input.focus(); " +
                    "}, 300);", null
        )

        updateTabSelection(provider)
    }

    // ─── WebView factory ─────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewForProvider(provider: Provider): WebView {
        val webView = WebView(this)
        registerForContextMenu(webView)

        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.")) {
                    Toast.makeText(context, R.string.error_copy, Toast.LENGTH_LONG).show()
                    return true
                }
                return false
            }

            override fun onShowFileChooser(
                wv: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                mUploadMessage?.onReceiveValue(null)
                mUploadMessage = filePathCallback
                
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "File Chooser"))
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        request.deny() // Deny for now, user taps again after granting
                    }
                } else {
                    request.deny()
                }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val popupWebView = WebView(context)
                popupWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportMultipleWindows(true)
                    userAgentString = modUserAgent()
                }

                val popupDialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                popupDialog.setContentView(popupWebView)
                popupDialog.show()

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        popupDialog.dismiss()
                    }
                }
                popupWebView.webViewClient = WebViewClient()

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!restricted) return null
                if (request.url.toString() == "about:blank") return null
                if (!request.url.toString().startsWith("https://")) {
                    Log.d(TAG, "[intercept][NON-HTTPS] Blocked: ${request.url}")
                    return WebResourceResponse("text/javascript", "UTF-8", null)
                }
                val host = request.url.host
                if (!provider.isAllowed(host)) {
                    Log.d(TAG, "[intercept][BLOCKED] $host for ${provider.label}")
                    if (host == "login.microsoftonline.com" || host == "appleid.apple.com") {
                        view.post {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_microsoft_google),
                                Toast.LENGTH_LONG
                            ).show()
                            resetChat()
                        }
                    }
                    if (request.url.toString().contains("gravatar.com/avatar/")) {
                        return try {
                            val inputStream = assets.open("avatar.png")
                            WebResourceResponse("image/png", "UTF-8", inputStream)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    return WebResourceResponse("text/javascript", "UTF-8", null)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!restricted) return false
                if (request.url.toString() == "about:blank") return false
                if (!request.url.toString().startsWith("https://")) {
                    Log.d(TAG, "[override][NON-HTTPS] Blocked: ${request.url}")
                    return true
                }
                val host = request.url.host
                if (!provider.isAllowed(host)) {
                    Log.d(TAG, "[override][BLOCKED] $host for ${provider.label}")
                    if (host == "login.microsoftonline.com" || host == "appleid.apple.com") {
                        view.post {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_microsoft_google),
                                Toast.LENGTH_LONG
                            ).show()
                            resetChat()
                        }
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest?,
                error: WebResourceError
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "[onReceivedError] ${error.errorCode}: ${error.description} @ ${request.url}")
                }
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val source = Uri.parse(url)
            Log.d(TAG, "DownloadManager [${provider.label}]: $url")
            val dlRequest = DownloadManager.Request(source).apply {
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("Accept", "text/html, application/xhtml+xml, */*")
                addRequestHeader("Accept-Language", "en-US,en;q=0.7")
                addRequestHeader("Referer", url)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                
                var filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition)
                if (filename == null) {
                    filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype)
                }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            
            Toast.makeText(this, getString(R.string.download) + "\n" + dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "file").toString(), Toast.LENGTH_SHORT).show() // minor hack, will fix toast next
            
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(dlRequest)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            builtInZoomControls = false
            databaseEnabled = true
            displayZoomControls = false
            saveFormData = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = modUserAgent()
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return webView
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun updateTabSelection(selected: Provider) {
        for ((key, btn) in tabButtons) {
            if (key == selected) {
                btn.setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_ATOP)
                btn.setBackgroundColor(0xFF2A2A2A.toInt())
            } else {
                btn.setColorFilter(0x80FFFFFF.toInt(), PorterDuff.Mode.SRC_ATOP)
                btn.background = null
            }
        }
    }

    fun resetChat() {
        val current = webViews[currentProvider] ?: return
        current.clearCache(true)
        current.clearFormData()
        current.clearHistory()
        current.clearMatches()
        current.clearSslPreferences()
        cookieManager.removeSessionCookie()
        cookieManager.removeAllCookie()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        current.loadUrl(currentProvider.url)
    }

    fun modUserAgent(): String {
        val newPrefix = "Mozilla/5.0 (X11; Linux ${System.getProperty("os.arch")})"
        var newUserAgent = WebSettings.getDefaultUserAgent(context)
        val prefix = newUserAgent.substring(0, newUserAgent.indexOf(")") + 1)
        try {
            newUserAgent = newUserAgent.replace(prefix, newPrefix)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newUserAgent
    }

    // ─── Back button ─────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            val active = webViews[currentProvider]
            if (active != null && active.canGoBack() && active.url != "about:blank") {
                active.goBack()
            } else {
                finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ─── Context menu ─────────────────────────────────────────────────────────

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val active = webViews[currentProvider] ?: return
        val result = active.hitTestResult
        var url = ""
        
        if (result.extra != null) {
            if (result.type == WebView.HitTestResult.IMAGE_TYPE) {
                url = result.extra!!
                val source = Uri.parse(url)
                val request = DownloadManager.Request(source).apply {
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                    addRequestHeader("Accept", "text/html, application/xhtml+xml, */*")
                    addRequestHeader("Accept-Language", "en-US,en;q=0.7")
                    addRequestHeader("Referer", url)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    
                    val filename = URLUtil.guessFileName(url, null, "image/jpeg")
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    Toast.makeText(context, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show()
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } else if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    val handlerThread = HandlerThread("HandlerThread").apply { start() }
                    val backgroundHandler = Handler(handlerThread.looper)
                    val msg = backgroundHandler.obtainMessage()
                    active.requestFocusNodeHref(msg)
                    url = msg.data.getString("url") ?: ""
                } else {
                    url = result.extra!!
                }
                
                if (url.isNotEmpty()) {
                    val host = Uri.parse(url).host
                    if (host != null && !currentProvider.isAllowed(host)) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(getString(R.string.app_name), url)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
