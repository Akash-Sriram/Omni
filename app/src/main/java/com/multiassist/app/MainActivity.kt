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
import androidx.preference.PreferenceManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.URLUtilCompat
import org.woheller69.freeDroidWarn.FreeDroidWarn
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    private val webViews: MutableMap<Provider, WebView> = EnumMap(Provider::class.java)
    private var currentProvider = Provider.CHATGPT

    private lateinit var webViewContainer: FrameLayout
    private lateinit var providerTabs: LinearLayout
    private val tabButtons: MutableMap<Provider, ImageButton> = EnumMap(Provider::class.java)
    private lateinit var cookieManager: CookieManager

    private val context: Context = this
    private var restricted = true
    private var activeProviders: Set<String> = emptySet()
    private var activeTheme: String = ""

    private var mUploadMessage: ValueCallback<Array<Uri>>? = null
    private var mCameraPhotoPath: String? = null
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // Activity Result API for Speech Recognition
    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0].replace("'", "\\'").replace("\n", " ")
                val active = webViews[currentProvider]
                active?.evaluateJavascript(
                    """
                    (function() {
                        var input = document.querySelector('textarea, [contenteditable="true"]');
                        if (input) {
                            if (input.tagName === 'TEXTAREA') {
                                input.value = (input.value ? input.value + ' ' : '') + '$spokenText';
                            } else {
                                input.innerText = (input.innerText ? input.innerText + ' ' : '') + '$spokenText';
                            }
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            input.focus();
                        }
                    })();
                    """.trimIndent(), null
                )
            }
        }
    }

    // Activity Result API for file choosing
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (result.data == null || result.data?.data == null) {
                // If there is no data, then we may have taken a photo
                if (mCameraPhotoPath != null) {
                    results = arrayOf(Uri.parse(mCameraPhotoPath))
                }
            } else {
                val dataString = result.data?.dataString
                if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }
        mUploadMessage?.onReceiveValue(results)
        mUploadMessage = null
        mCameraPhotoPath = null
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
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val allProviders = Provider.values().map { it.name }.toSet()
        val currentEnabledKeys = sp.getStringSet("enabled_providers", allProviders)?.toSet() ?: allProviders
        val currentTheme = sp.getString("theme_override", "SYSTEM") ?: "SYSTEM"
        
        if (activeProviders.isNotEmpty() && (currentEnabledKeys != activeProviders || currentTheme != activeTheme)) {
            recreate()
            return
        }

        // Instantly apply voice FAB and pull-to-refresh visibility without app restart
        val showVoiceFab = sp.getBoolean("show_voice_fab", true)
        findViewById<ImageButton>(R.id.fab_voice)?.visibility = if (showVoiceFab) View.VISIBLE else View.GONE

        val enablePullToRefresh = sp.getBoolean("enable_pull_to_refresh", true)
        if (::swipeRefresh.isInitialized) {
            val active = webViews[currentProvider]
            swipeRefresh.isEnabled = enablePullToRefresh && (active?.scrollY == 0 && active?.canScrollVertically(-1) == false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        tts = TextToSpeech(this, this)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        // Apply theme before view inflation
        activeTheme = prefs.getString("theme_override", "SYSTEM") ?: "SYSTEM"
        when (activeTheme) {
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
        activeProviders = prefs.getStringSet("enabled_providers", allProviders)?.toSet() ?: allProviders
        
        val defaultOrder = Provider.values().joinToString(",") { it.name }
        val savedOrderStr = prefs.getString("provider_order", defaultOrder) ?: defaultOrder
        val orderedKeys = savedOrderStr.split(",").map { it.trim() }
        
        val enabledProviders = Provider.values()
            .filter { activeProviders.contains(it.name) }
            .sortedBy { p ->
                val idx = orderedKeys.indexOf(p.name)
                if (idx >= 0) idx else Int.MAX_VALUE
            }
            .ifEmpty { listOf(Provider.CHATGPT) }

        webViewContainer = findViewById(R.id.webview_container)
        providerTabs = findViewById(R.id.provider_tabs)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        val enablePullToRefresh = prefs.getBoolean("enable_pull_to_refresh", true)
        swipeRefresh.isEnabled = enablePullToRefresh

        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val active = webViews[currentProvider]
            if (active != null) {
                // If webview can scroll up OR down (i.e. user is anywhere in a scrollable page), block swipeRefresh
                active.canScrollVertically(-1) || active.scrollY > 0
            } else {
                false
            }
        }

        swipeRefresh.setOnRefreshListener {
            val active = webViews[currentProvider]
            if (active != null) {
                active.reload()
            } else {
                swipeRefresh.isRefreshing = false
            }
        }

        val showVoiceFab = prefs.getBoolean("show_voice_fab", true)
        val fabVoice = findViewById<ImageButton>(R.id.fab_voice)
        fabVoice.visibility = if (showVoiceFab) View.VISIBLE else View.GONE
        fabVoice.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your prompt for ${currentProvider.label}...")
            }
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Speech recognition not supported on this device.", Toast.LENGTH_SHORT).show()
            }
        }

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

        // Show default provider (if it's not enabled, fallback to first enabled)
        val initialProvider = if (enabledProviders.contains(defaultTab)) defaultTab else enabledProviders.first()
        switchProvider(initialProvider)

        // Preload remaining enabled AI models in background after short delay for instant switching
        Handler(mainLooper).postDelayed({
            for (p in enabledProviders) {
                if (p != initialProvider && !webViews.containsKey(p)) {
                    val wv = createWebViewForProvider(p)
                    wv.visibility = View.GONE
                    webViews[p] = wv
                    webViewContainer.addView(
                        wv,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    )
                    wv.loadUrl(p.url)
                    wv.onPause() // Keep paused in background until user switches to it
                }
            }
        }, 1200)
    }



    // ─── Provider switching ───────────────────────────────────────────────────

    private fun switchProvider(provider: Provider) {
        webViews.forEach { (p, wv) ->
            if (p != provider) {
                wv.visibility = View.GONE
                wv.onPause()
            }
        }

        var active = webViews[provider]
        if (active == null) {
            active = createWebViewForProvider(provider)
            webViews[provider] = active
            webViewContainer.addView(
                active,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            active.loadUrl(provider.url)
        }

        active.onResume()
        active.visibility = View.VISIBLE
        currentProvider = provider

        active.evaluateJavascript(
            "setTimeout(function() { " +
                    "var input = document.querySelector('textarea, [contenteditable=\"true\"]'); " +
                    "if (input) input.focus(); " +
                    "}, 300);", null
        )

        updateTabSelection(provider)
    }

    // ─── WebView factory ─────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebViewForProvider(provider: Provider): WebView {
        val webView = WebView(this)
        registerForContextMenu(webView)

        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(OmniBridge(this, webView), "OmniBridge")

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
                
                var takePictureIntent: Intent? = null
                val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val imageFileName = "JPEG_" + timeStamp + "_"
                val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                try {
                    val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
                    mCameraPhotoPath = "file:" + imageFile.absolutePath
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (intent.resolveActivity(packageManager) != null) {
                        val photoURI: Uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            imageFile
                        )
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                        takePictureIntent = intent
                    }
                } catch (ex: IOException) {
                    Log.e(TAG, "Unable to create Image File", ex)
                }

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                val intentArray: Array<Intent> = takePictureIntent?.let { arrayOf(it) } ?: arrayOf()

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Choose an action")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                }

                fileChooserLauncher.launch(chooserIntent)
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
                val popupDialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }

                // Security & Control Top Bar
                val topBar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    setPadding(32, 24, 32, 24)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val titleText = android.widget.TextView(context).apply {
                    text = "🔒 Security Auth Browser"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val closeBtn = android.widget.Button(context).apply {
                    text = "✕"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0x00000000)
                    textSize = 18f
                    setOnClickListener { popupDialog.dismiss() }
                }

                topBar.addView(titleText)
                topBar.addView(closeBtn)
                layout.addView(topBar)

                val popupWebView = WebView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                }

                layout.addView(popupWebView)
                popupDialog.setContentView(layout)
                popupDialog.show()

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        popupDialog.dismiss()
                    }
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null) {
                            val host = Uri.parse(url).host ?: ""
                            titleText.text = "🔒 $host"
                            
                            // Auto dismiss micro browser when redirecting back to main provider domain
                            val isAuthPage = host.contains("google.com") || host.contains("accounts.") || host.contains("auth0.com") || host.contains("appleid") || host.contains("volces.com") || host.contains("volcengine.com")
                            if (currentProvider.isAllowed(host) && !isAuthPage) {
                                cookieManager.flush()
                                popupDialog.dismiss()
                                view?.postDelayed({
                                    cookieManager.flush()
                                    webViews[currentProvider]?.reload()
                                }, 150)
                            }
                        }
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                val js = """
                    (function() {
                        // 1. Custom UI Injection: Hide annoying banners
                        const style = document.createElement('style');
                        style.innerHTML = `
                            [class*='banner'], [class*='announcement'], 
                            div:has(> a[href*='upgrade']) { 
                                display: none !important; 
                            }
                        `;
                        document.head.appendChild(style);

                        // 2. Haptic Feedback Integration
                        document.body.addEventListener('click', function(e) {
                            let target = e.target;
                            while (target != null && target !== document.body) {
                                if (target.tagName === 'BUTTON' || target.tagName === 'A' || target.getAttribute('role') === 'button') {
                                    window.OmniBridge.vibrate();
                                    break;
                                }
                                target = target.parentElement;
                            }
                        // 3. TTS Read-Aloud: Double-tap text or long-press to read response
                        document.body.addEventListener('dblclick', function(e) {
                            let text = window.getSelection().toString().trim();
                            if (!text && e.target) {
                                text = e.target.innerText || e.target.textContent;
                            }
                            if (text && text.length > 2) {
                                window.OmniBridge.speak(text.substring(0, 4000));
                            }
                        });
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }

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
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, request.url)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open external link", e)
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
                    if (error.errorCode == WebViewClient.ERROR_HOST_LOOKUP || error.errorCode == WebViewClient.ERROR_TIMEOUT || error.errorCode == WebViewClient.ERROR_CONNECT) {
                        view.loadUrl("file:///android_asset/offline.html")
                    }
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

        @Suppress("DEPRECATION")
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
        
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            if (::swipeRefresh.isInitialized && PreferenceManager.getDefaultSharedPreferences(this).getBoolean("enable_pull_to_refresh", true)) {
                // Only enable refresh if both scrollY is 0 AND cannot scroll up
                swipeRefresh.isEnabled = (scrollY == 0 && !v.canScrollVertically(-1))
            }
        }
        
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

    @Suppress("DEPRECATION")
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
        val defaultUA = WebSettings.getDefaultUserAgent(context)
        // Strip out "; wv" or "wv" which signals an embedded Android WebView to Cloudflare & OpenAI
        return defaultUA.replace("; wv", "").replace(" wv", "")
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
        
        if (result.extra != null) {
            val url: String
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
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsInitialized = true
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ─── Javascript Interface ────────────────────────────────────────────────
    
    inner class OmniBridge(private val context: Context, private val webView: WebView) {
        @JavascriptInterface
        fun vibrate() {
            webView.post {
                webView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            if (isTtsInitialized && text.isNotBlank()) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "OmniTTS")
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            if (isTtsInitialized) {
                tts?.stop()
            }
        }
    }
}
