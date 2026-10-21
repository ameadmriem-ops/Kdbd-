package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import android.util.LruCache
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.json.JSONObject

data class NotificationNavigationData(
    val page: String = "",
    val firebaseId: String = "",
    val episode: String = "",
    val url: String = "",
    val action: String = "",
    val contentTitle: String = ""
)

data class DownloadRequest(
    val url: String,
    val animeTitle: String,
    val episodeTitle: String,
    val episodeKey: String
)

data class DownloadItemInfo(
    val downloadId: Long,
    val episodeKey: String,
    val fileName: String,
    val animeTitle: String,
    val episodeTitle: String,
    var status: Int,
    var progress: Int = 0
)

/**
 * High-performance image cache manager for Black Anime:
 * - 250 MB persistent disk cache via OkHttp
 * - 30 MB in-memory LruCache for instant 0ms hits
 * - Concurrency synchronization to deduplicate in-flight requests for the same URL
 * - Automatic Content-Type detection and CORS/Cache header injection
 */
object PosterImageCache {
    private const val TAG = "PosterImageCache"
    private var okHttpClient: OkHttpClient? = null

    // 30MB RAM cache for instant 0ms repeated card displays
    private val memoryCache = object : LruCache<String, ByteArray>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // Locks to deduplicate concurrent requests for the identical poster URL
    private val inFlightLocks = ConcurrentHashMap<String, Any>()

    fun init(context: Context) {
        if (okHttpClient != null) return
        try {
            val cacheDir = File(context.cacheDir, "black_anime_posters_disk_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val diskCacheSize = 250L * 1024L * 1024L // 250 MB disk cache
            val cache = Cache(cacheDir, diskCacheSize)

            okHttpClient = OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val original = chain.proceed(chain.request())
                    // Force 30 days immutable disk cache for all successful poster responses
                    original.newBuilder()
                        .removeHeader("Pragma")
                        .removeHeader("Cache-Control")
                        .header("Cache-Control", "public, max-age=2592000, immutable")
                        .build()
                }
                .build()
            Log.d(TAG, "PosterImageCache initialized with 250MB disk cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PosterImageCache: ${e.message}")
        }
    }

    fun isImageRequest(url: Uri, request: WebResourceRequest): Boolean {
        val host = (url.host ?: "").lowercase()
        // Never intercept internal asset loader
        if (host == "hamza-4b70c.firebaseapp.com") return false
        // Never intercept Firebase, Auth, Firestore, Google APIs, or Ads
        if (host.contains("firestore.googleapis.com") || host.contains("identitytoolkit.googleapis.com") ||
            host.contains("securetoken.googleapis.com") || host.contains("googleads") ||
            host.contains("doubleclick.net") || host.contains("admob") || host.contains("gstatic.com")) {
            return false
        }
        val path = (url.path ?: "").lowercase()
        // Never intercept media streams
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp3")) {
            return false
        }
        // Image extensions
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") ||
            path.endsWith(".bmp") || path.endsWith(".ico")) {
            return true
        }
        // Known image providers
        if (host.contains("images.unsplash.com") || host.contains("media-amazon.com") ||
            host.contains("image.tmdb.org") || host.contains("i.imgur.com") ||
            (host.contains("firebasestorage.googleapis.com") && url.toString().contains("/o/"))) {
            return true
        }
        // Request Accept header
        val accept = request.requestHeaders?.get("Accept") ?: request.requestHeaders?.get("accept")
        if (accept != null && accept.startsWith("image/")) {
            return true
        }
        return false
    }

    fun fetchImage(urlStr: String): WebResourceResponse? {
        val client = okHttpClient ?: return null

        // 1. Ultra-fast RAM check (0ms)
        val inRam = memoryCache.get(urlStr)
        if (inRam != null && inRam.isNotEmpty()) {
            val mimeType = guessMimeType(urlStr, null)
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=2592000, immutable"
            )
            return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, ByteArrayInputStream(inRam))
        }

        // 2. Request deduplication: ensure duplicate in-flight requests for the same URL share execution
        val lock = inFlightLocks.computeIfAbsent(urlStr) { Any() }
        synchronized(lock) {
            // Re-check RAM in case another thread just fetched it
            val cachedNow = memoryCache.get(urlStr)
            if (cachedNow != null && cachedNow.isNotEmpty()) {
                val mimeType = guessMimeType(urlStr, null)
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "public, max-age=2592000, immutable"
                )
                return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, ByteArrayInputStream(cachedNow))
            }

            try {
                val req = Request.Builder()
                    .url(urlStr)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android; BlackAnime/1.0)")
                    .build()

                val response = client.newCall(req).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        memoryCache.put(urlStr, bytes)
                        val mimeType = guessMimeType(urlStr, response.header("Content-Type"))
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "public, max-age=2592000, immutable"
                        )
                        return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached image: $urlStr (${e.message})")
            } finally {
                inFlightLocks.remove(urlStr)
            }
        }
        return null
    }

    private fun guessMimeType(url: String, headerContentType: String?): String {
        if (!headerContentType.isNullOrBlank()) {
            val base = headerContentType.split(";")[0].trim().lowercase()
            if (base.startsWith("image/")) return base
        }
        val lower = url.lowercase()
        return when {
            lower.contains(".png") -> "image/png"
            lower.contains(".webp") -> "image/webp"
            lower.contains(".gif") -> "image/gif"
            lower.contains(".svg") -> "image/svg+xml"
            lower.contains(".bmp") -> "image/bmp"
            lower.contains(".ico") -> "image/x-icon"
            else -> "image/jpeg"
        }
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AdMob"
        private const val FCM_TAG = "FCM_Notification"
        const val DEFAULT_REWARDED_AD_UNIT_ID = "ca-app-pub-2277779478101583/1404104591"
        const val EPISODE_REWARDED_AD_UNIT_ID = "ca-app-pub-2277779478101583/9116970862"
        const val PREFS_ADMOB = "admob_system_prefs"
        const val KEY_EXEMPT_UNTIL = "admob_exempt_until"
        const val KEY_FIVE_ADS_COUNT = "admob_five_ads_count"
        const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1003
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var adMobBridge: AdMobBridge? = null
    private var isWebViewLoaded: Boolean = false
    private var pendingFcmNotification: NotificationNavigationData? = null
    @Volatile
    var isInputFocusedInWebView: Boolean = false
    @Volatile
    var isKeyboardActive: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var pendingDownloadRequest: DownloadRequest? = null
    val activeDownloads = ConcurrentHashMap<Long, DownloadItemInfo>()
    val episodeKeyToDownloadId = ConcurrentHashMap<String, Long>()
    private var isProgressPollingActive: Boolean = false
    private var downloadCompleteReceiver: BroadcastReceiver? = null

    fun setInputFocusedFromJs(isFocused: Boolean) {
        isInputFocusedInWebView = isFocused
    }

    // Native Media3 ExoPlayer components
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerTopBar: LinearLayout
    private lateinit var playerTitleTextView: TextView
    private lateinit var playerProgressBar: ProgressBar
    private lateinit var playerErrorContainer: LinearLayout
    private lateinit var playerErrorTextView: TextView
    private var exoPlayer: ExoPlayer? = null
    private var currentStreamUrl: String? = null
    private var currentStreamTitle: String? = null
    private var isPlayerFullscreen: Boolean = false
    private var playbackTicker: Runnable? = null
    private val playbackHandler = Handler(Looper.getMainLooper())

    private fun startPlaybackTicker() {
        stopPlaybackTicker()
        val runnable = object : Runnable {
            override fun run() {
                if (exoPlayer?.isPlaying == true) {
                    dispatchPlaybackTick(1)
                    playbackHandler.postDelayed(this, 1000)
                } else {
                    stopPlaybackTicker()
                }
            }
        }
        playbackTicker = runnable
        playbackHandler.postDelayed(runnable, 1000)
    }

    private fun stopPlaybackTicker() {
        playbackTicker?.let { playbackHandler.removeCallbacks(it) }
        playbackTicker = null
    }

    private fun dispatchPlaybackTick(seconds: Int) {
        val script = "if(window.onNativeVideoTick){window.onNativeVideoTick($seconds);}"
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PosterImageCache.init(applicationContext)
        
        // Enable true edge-to-edge immersive full-screen mode across the entire application
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            isKeyboardActive = imeVisible
            if (imeVisible) {
                isInputFocusedInWebView = true
            }
            insets
        }

        applyImmersiveFullScreen()

        // Ensure Firebase is initialized
        MyFirebaseMessagingService.ensureFirebaseInitialized(this)

        // Initialize Notification Channel and FCM
        MyFirebaseMessagingService.createNotificationChannel(this)
        requestNotificationPermission()
        initializeFcm()

        setupContentView()
        setupBackNavigation()
        registerDownloadReceiver()
        handleNotificationIntent(intent)

        // Initialize Google Mobile Ads SDK (AdMob) once at startup after views are initialized
        Log.d(TAG, "AdMob: [Init] Starting Google Mobile Ads SDK initialization...")
        MobileAds.initialize(this) { initializationStatus ->
            for ((adapterClass, status) in initializationStatus.adapterStatusMap) {
                Log.d(
                    TAG,
                    "AdMob: [Init] Adapter: $adapterClass, State: ${status.initializationState}, Latency: ${status.latency}ms, Description: ${status.description}"
                )
            }
            Log.d(TAG, "AdMob: [Init] Google Mobile Ads SDK initialized successfully.")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming webView: ${e.message}")
        }
        applyImmersiveFullScreen()
        if (isWebViewLoaded) {
            val sysGranted = isSystemNotificationsAllowed()
            val appEnabled = MyFirebaseMessagingService.isAppNotificationsEnabled(this)
            webView.post {
                webView.evaluateJavascript("if(window.onNotificationStatusSync){window.onNotificationStatusSync($appEnabled, $sysGranted);}", null)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                MyFirebaseMessagingService.setAppNotificationsEnabled(this, true)
            }
            if (isWebViewLoaded) {
                val appEnabled = MyFirebaseMessagingService.isAppNotificationsEnabled(this)
                val sysGranted = isSystemNotificationsAllowed()
                webView.post {
                    webView.evaluateJavascript("if(window.onNotificationPermissionResult){window.onNotificationPermissionResult($granted, $appEnabled, $sysGranted);}", null)
                }
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingDownloadRequest?.let { req ->
                    startDownloadInternal(req)
                    pendingDownloadRequest = null
                }
            } else {
                val req = pendingDownloadRequest
                pendingDownloadRequest = null
                val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                runOnUiThread {
                    if (permanentlyDenied) {
                        showPermissionSettingsDialog()
                    } else {
                        Toast.makeText(this@MainActivity, "تم إلغاء التنزيل لعدم توفر إذن التخزين", Toast.LENGTH_SHORT).show()
                        val epKey = req?.episodeKey ?: ""
                        val js = "if(window.onEpisodeDownloadStatusChanged){window.onEpisodeDownloadStatusChanged('$epKey', 'FAILED', 0, 'PERMISSION_DENIED');}"
                        webView.evaluateJavascript(js, null)
                    }
                }
            }
        }
    }

    fun isSystemNotificationsAllowed(): Boolean {
        val managerCompat = androidx.core.app.NotificationManagerCompat.from(this)
        if (!managerCompat.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun initializeFcm() {
        try {
            MyFirebaseMessagingService.ensureFirebaseInitialized(this)
            MyFirebaseMessagingService.startRealtimeBroadcastListener(this)
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(FCM_TAG, "FCM Device Registration Token: $token")
                    MyFirebaseMessagingService.saveToken(this, token)
                } else {
                    Log.w(FCM_TAG, "Fetching FCM registration token failed", task.exception)
                }
            }
            if (MyFirebaseMessagingService.isAppNotificationsEnabled(this)) {
                FirebaseMessaging.getInstance().subscribeToTopic("all_users")
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(FCM_TAG, "Subscribed to topic: all_users")
                        }
                    }
                FirebaseMessaging.getInstance().subscribeToTopic("anime_updates")
            } else {
                Log.d(FCM_TAG, "App notifications disabled by user preference; skipping topic subscription.")
            }
        } catch (e: Exception) {
            Log.e(FCM_TAG, "Error initializing FCM: ${e.message}", e)
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val extras = intent.extras ?: return

        val page = extras.getString("fcm_page")
            ?: extras.getString("page")
            ?: extras.getString("target_page")
            ?: extras.getString("screen")
            ?: ""

        val firebaseId = extras.getString("fcm_firebaseId")
            ?: extras.getString("firebaseId")
            ?: extras.getString("id")
            ?: extras.getString("contentId")
            ?: extras.getString("animeId")
            ?: extras.getString("movieId")
            ?: ""

        val episode = extras.getString("fcm_episode")
            ?: extras.getString("episode")
            ?: extras.getString("episodeIndex")
            ?: extras.getString("episodeNumber")
            ?: extras.getString("ep")
            ?: ""

        val url = extras.getString("fcm_url")
            ?: extras.getString("url")
            ?: extras.getString("link")
            ?: ""

        val action = extras.getString("fcm_action")
            ?: extras.getString("action")
            ?: (if (episode.isNotBlank()) "play" else "")

        val contentTitle = extras.getString("contentTitle")
            ?: extras.getString("fcm_contentTitle")
            ?: extras.getString("animeTitle")
            ?: extras.getString("movieTitle")
            ?: extras.getString("name")
            ?: ""

        val hasTarget = page.isNotBlank() || firebaseId.isNotBlank() || episode.isNotBlank() || url.isNotBlank() || contentTitle.isNotBlank()

        if (hasTarget) {
            Log.d(FCM_TAG, "Handling notification intent: page=$page, firebaseId=$firebaseId, episode=$episode, action=$action, contentTitle=$contentTitle, url=$url")
            val navData = NotificationNavigationData(
                page = page,
                firebaseId = firebaseId,
                episode = episode,
                url = url,
                action = action,
                contentTitle = contentTitle
            )
            if (isWebViewLoaded) {
                dispatchFcmNavigation(navData)
            } else {
                pendingFcmNotification = navData
            }
        }
    }

    private fun dispatchFcmNavigation(navData: NotificationNavigationData) {
        val json = JSONObject().apply {
            put("page", navData.page)
            put("firebaseId", navData.firebaseId)
            put("id", navData.firebaseId)
            put("episode", navData.episode)
            put("episodeIndex", navData.episode)
            put("url", navData.url)
            put("action", navData.action)
            put("contentTitle", navData.contentTitle)
        }.toString()

        val script = "if(window.handleFcmNotification){window.handleFcmNotification($json);}"
        Log.d(FCM_TAG, "Dispatching notification navigation to WebView: $json")
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupContentView() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        fullscreenContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        setupNativePlayerLayout()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("hamza-4b70c.firebaseapp.com")
            .addPathHandler("/assets/", AssetsPathHandler(this@MainActivity))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                blockNetworkImage = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    offscreenPreRaster = true
                }
                setSupportMultipleWindows(true)
                try {
                    val currentUa = userAgentString
                    if (currentUa.contains("; wv")) {
                        userAgentString = currentUa.replace("; wv", "")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error sanitizing userAgent: ${e.message}")
                }
            }

            // Expose Native AdMob Bridge to JavaScript
            val bridge = AdMobBridge(this@MainActivity, this)
            adMobBridge = bridge
            addJavascriptInterface(bridge, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.host == "hamza-4b70c.firebaseapp.com" && (url.path == null || url.path?.startsWith("/assets/") == true)) {
                        return assetLoader.shouldInterceptRequest(url)
                    }
                    if (PosterImageCache.isImageRequest(url, request)) {
                        val cachedResponse = PosterImageCache.fetchImage(url.toString())
                        if (cachedResponse != null) {
                            return cachedResponse
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyImmersiveFullScreen()
                    isWebViewLoaded = true
                    pendingFcmNotification?.let { navData ->
                        dispatchFcmNavigation(navData)
                        pendingFcmNotification = null
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val popupWebView = WebView(this@MainActivity).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportMultipleWindows(true)
                            try {
                                val currentUa = userAgentString
                                if (currentUa.contains("; wv")) {
                                    userAgentString = currentUa.replace("; wv", "")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Popup UA error: ${e.message}")
                            }
                        }
                    }
                    val dialog = android.app.Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.setContentView(popupWebView)
                    dialog.setOnDismissListener {
                        popupWebView.destroy()
                    }
                    popupWebView.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            dialog.dismiss()
                        }
                    }
                    popupWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return false
                        }
                    }
                    dialog.show()
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = popupWebView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    fullscreenContainer.addView(view)
                    fullscreenContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    applyImmersiveFullScreen()
                }

                override fun onHideCustomView() {
                    super.onHideCustomView()
                    if (customView == null) return
                    fullscreenContainer.removeView(customView)
                    fullscreenContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    applyImmersiveFullScreen()
                }

                override fun getDefaultVideoPoster(): Bitmap? {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
            }
        }

        rootLayout.addView(webView)
        rootLayout.addView(fullscreenContainer)
        rootLayout.addView(playerContainer)
        setContentView(rootLayout)

        webView.loadUrl("https://hamza-4b70c.firebaseapp.com/assets/index.html")
    }

    @OptIn(UnstableApi::class)
    private fun setupNativePlayerLayout() {
        playerContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
            controllerShowTimeoutMs = 5000
            controllerAutoShow = false
            controllerHideOnTouch = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                playerTopBar.visibility = visibility
            })
            setFullscreenButtonClickListener { isFullscreen ->
                toggleFullscreen(isFullscreen)
            }
        }
        playerContainer.addView(playerView)

        val density = resources.displayMetrics.density

        // Top bar overlay for title and back button (integrated with controller)
        playerTopBar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setOnClickListener {
                playerView.showController()
            }
        }

        // Back button identical to .back-btn used across the rest of the application
        val backButton = TextView(this).apply {
            val padVertical = (8 * density).toInt()
            val padHorizontal = (18 * density).toInt()
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)

            text = "← رجوع"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false

            val normalBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#181818"))
                setStroke((1 * density).toInt(), Color.parseColor("#333333"))
            }

            val pressedBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#222222"))
                setStroke((1 * density).toInt(), Color.parseColor("#e50914"))
            }

            val stateList = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(android.R.attr.state_focused), pressedBg)
                addState(intArrayOf(), normalBg)
            }

            val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            background = RippleDrawable(rippleColor, stateList, null)

            elevation = 4 * density
            isClickable = true
            isFocusable = true
            contentDescription = "رجوع"

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            setOnClickListener {
                if (isPlayerFullscreen) {
                    exitFullscreen()
                } else {
                    closeNativePlayer()
                }
            }
        }
        playerTopBar.addView(backButton)

        playerTitleTextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            text = "BLACK ANIME"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        playerTopBar.addView(playerTitleTextView)

        val reportPlayerButton = TextView(this).apply {
            val padVertical = (7 * density).toInt()
            val padHorizontal = (12 * density).toInt()
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)

            text = "🚩 إبلاغ"
            setTextColor(Color.parseColor("#FFB4B4"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false

            val normalBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#2A0808"))
                setStroke((1 * density).toInt(), Color.parseColor("#661A1A"))
            }

            val pressedBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#440D0D"))
                setStroke((1 * density).toInt(), Color.parseColor("#E50914"))
            }

            val stateList = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(android.R.attr.state_focused), pressedBg)
                addState(intArrayOf(), normalBg)
            }

            val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FF5555"))
            background = RippleDrawable(rippleColor, stateList, null)

            elevation = 4 * density
            isClickable = true
            isFocusable = true
            contentDescription = "الإبلاغ عن مشكلة"

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            setOnClickListener {
                closeNativePlayer()
                webView.post {
                    webView.evaluateJavascript("if(window.openReportModalFromActivePlayback){window.openReportModalFromActivePlayback();}", null)
                }
            }
        }
        playerTopBar.addView(reportPlayerButton)

        playerContainer.addView(playerTopBar)

        // Buffering ProgressBar
        playerProgressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        playerContainer.addView(playerProgressBar)

        // Error overlay
        playerErrorContainer = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#EE000000"))
            visibility = View.GONE
        }

        val errorIcon = TextView(this).apply {
            text = "⚠️"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        playerErrorContainer.addView(errorIcon)

        playerErrorTextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 30
            }
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "تعذر تشغيل هذا البث"
        }
        playerErrorContainer.addView(playerErrorTextView)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val retryButton = Button(this).apply {
            text = "🔄 إعادة المحاولة"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E50914"))
            setOnClickListener {
                currentStreamUrl?.let { url ->
                    playStream(url, currentStreamTitle ?: "")
                }
            }
        }
        buttonsRow.addView(retryButton)

        val closeButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 20
            }
            text = "إغلاق"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setOnClickListener {
                closeNativePlayer()
            }
        }
        buttonsRow.addView(closeButton)

        val reportErrorButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 20
            }
            text = "🚩 الإبلاغ عن مشكلة"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#8A1C1C"))
            setOnClickListener {
                closeNativePlayer()
                webView.post {
                    webView.evaluateJavascript("if(window.openReportModalFromActivePlayback){window.openReportModalFromActivePlayback('الحلقة لا تعمل');}", null)
                }
            }
        }
        buttonsRow.addView(reportErrorButton)

        playerErrorContainer.addView(buttonsRow)
        playerContainer.addView(playerErrorContainer)
    }

    @OptIn(UnstableApi::class)
    fun playStream(rawUrl: String, title: String) {
        var streamUrl = rawUrl.trim()
        if (streamUrl.isBlank()) {
            Log.e(TAG, "ExoPlayer: playStream called with blank URL")
            return
        }

        // Support Pixeldrain URLs (e.g. https://pixeldrain.com/u/ID or https://pixeldrain.com/api/file/ID)
        val pixeldrainPattern = Regex("""(?i)^(?:https?://)?(?:www\.)?pixeldrain\.com/(?:u|file|api/file)/([a-zA-Z0-9_-]+).*$""")
        val pdMatch = pixeldrainPattern.find(streamUrl)
        if (pdMatch != null) {
            val fileId = pdMatch.groupValues[1]
            streamUrl = "https://pixeldrain.com/api/file/$fileId"
            Log.d(TAG, "Pixeldrain direct video URL resolved: $streamUrl")
        }

        currentStreamUrl = streamUrl
        currentStreamTitle = title

        Log.d(TAG, "ExoPlayer: Playing stream: $streamUrl (Title: $title)")

        runOnUiThread {
            if (isPlayerFullscreen) {
                exitFullscreen()
            }
            playerContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            playerTitleTextView.text = title.ifBlank { "BLACK ANIME" }
            playerErrorContainer.visibility = View.GONE
            playerProgressBar.visibility = View.VISIBLE
            playerTopBar.visibility = View.GONE
            playerView.hideController()

            try {
                if (exoPlayer == null) {
                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .setConnectTimeoutMs(20000)
                        .setReadTimeoutMs(20000)
                        .setAllowCrossProtocolRedirects(true)
                        .setKeepPostFor302Redirects(true)
                        .setDefaultRequestProperties(mapOf(
                            "Accept" to "*/*",
                            "Accept-Encoding" to "identity"
                        ))

                    val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

                    val player = ExoPlayer.Builder(this)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                        .setSeekBackIncrementMs(15000)
                        .setSeekForwardIncrementMs(15000)
                        .build()

                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                startPlaybackTicker()
                            } else {
                                stopPlaybackTicker()
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    playerProgressBar.visibility = View.VISIBLE
                                }
                                Player.STATE_READY -> {
                                    playerProgressBar.visibility = View.GONE
                                    playerErrorContainer.visibility = View.GONE
                                    playerTopBar.visibility = View.GONE
                                    playerView.hideController()
                                    Log.d(TAG, "ExoPlayer: Playback state READY")
                                }
                                Player.STATE_ENDED -> {
                                    stopPlaybackTicker()
                                    playerProgressBar.visibility = View.GONE
                                }
                                Player.STATE_IDLE -> {
                                    stopPlaybackTicker()
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            stopPlaybackTicker()
                            Log.e(TAG, "ExoPlayer: onPlayerError - ${error.errorCodeName} (${error.errorCode}): ${error.message}", error)
                            playerProgressBar.visibility = View.GONE
                            val cause = error.cause
                            val userErrorMessage = when {
                                cause is HttpDataSource.InvalidResponseCodeException -> {
                                    when (cause.responseCode) {
                                        403 -> "خطأ 403: تم رفض الوصول إلى الملف من السيرفر (Forbidden / Access Denied)"
                                        404 -> "خطأ 404: ملف الفيديو غير موجود على السيرفر (File Not Found)"
                                        500 -> "خطأ 500: خطأ داخلي في سيرفر الفيديو (Internal Server Error)"
                                        502, 503 -> "خطأ ${cause.responseCode}: سيرفر الفيديو غير متاح حالياً (Server Unavailable)"
                                        else -> "خطأ HTTP ${cause.responseCode} من سيرفر الفيديو"
                                    }
                                }
                                cause is HttpDataSource.HttpDataSourceException -> {
                                    val msg = cause.message ?: ""
                                    when {
                                        msg.contains("Cleartext", ignoreCase = true) -> {
                                            "خطأ: حركة مرور HTTP غير المشفرة محظورة (Cleartext HTTP blocked)"
                                        }
                                        cause.cause is java.net.SocketTimeoutException -> {
                                            "مشكلة اتصال بالإنترنت: انتهت مهلة الاتصال بالسيرفر (Connection Timeout)"
                                        }
                                        cause.cause is java.net.ConnectException -> {
                                            "مشكلة اتصال بالإنترنت: فشل الاتصال بالسيرفر (Connection Failed)"
                                        }
                                        cause.cause is java.net.UnknownHostException -> {
                                            "تعذر الوصول لعنوان السيرفر أو الرابط غير صالح (Unknown Host / Invalid URL)"
                                        }
                                        else -> {
                                            "فشل الاتصال بسيرفر الفيديو: ${cause.message ?: "يرجى التحقق من توفر الملف والإنترنت"}"
                                        }
                                    }
                                }
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                                    "نوع صيغة ملف الفيديو غير مدعوم من المشغل (Unsupported Video Format)"
                                }
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                                    "صيغة ملف الفيديو تالفة أو غير صالحة (Malformed Media File)"
                                }
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                                    "مشكلة اتصال بالإنترنت: انتهت مهلة الاتصال بالسيرفر (Timeout)"
                                }
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                                    "مشكلة اتصال بالإنترنت: فشل الاتصال بشبكة الفيديو (Connection Failed)"
                                }
                                cause is java.io.IOException && cause.message?.contains("Cleartext", ignoreCase = true) == true -> {
                                    "حركة مرور HTTP محظورة (Cleartext HTTP blocked)"
                                }
                                else -> {
                                    "تعذر تشغيل هذا الملف: ${error.localizedMessage ?: "تأكد من عمل الرابط والسيرفر"}"
                                }
                            }
                            playerErrorTextView.text = userErrorMessage
                            playerErrorContainer.visibility = View.VISIBLE
                        }
                    })

                    playerView.player = player
                    exoPlayer = player
                }

                // Build HLS or Progressive MediaSource
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true)
                    .setKeepPostFor302Redirects(true)
                    .setDefaultRequestProperties(mapOf(
                        "Accept" to "*/*",
                        "Accept-Encoding" to "identity"
                    ))

                val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
                val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)

                val uri = Uri.parse(streamUrl)
                val isHls = streamUrl.contains(".m3u8", ignoreCase = true) ||
                        streamUrl.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                        streamUrl.contains("application/x-mpegURL", ignoreCase = true) ||
                        streamUrl.contains("application/x-mpegurl", ignoreCase = true)

                val mediaItemBuilder = MediaItem.Builder().setUri(uri)
                if (isHls) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                val mediaItem = mediaItemBuilder.build()

                val extractorsFactory = DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)

                val mediaSource = if (isHls) {
                    hlsMediaSourceFactory.createMediaSource(mediaItem)
                } else {
                    // For direct video files (MP4, MKV, WebM, Pixeldrain API, etc.)
                    ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItem)
                }

                exoPlayer?.apply {
                    stop()
                    setMediaSource(mediaSource)
                    prepare()
                    playWhenReady = true
                }
                playerTopBar.visibility = View.GONE
                playerView.hideController()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ExoPlayer stream: ${e.message}", e)
                playerProgressBar.visibility = View.GONE
                playerErrorTextView.text = "خطأ في تهيئة المشغل: ${e.message}"
                playerErrorContainer.visibility = View.VISIBLE
            }
        }
    }

    fun closeNativePlayer() {
        runOnUiThread {
            stopPlaybackTicker()
            if (isPlayerFullscreen) {
                exitFullscreen()
            }
            exoPlayer?.stop()
            playerView.hideController()
            playerTopBar.visibility = View.GONE
            playerContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            applyImmersiveFullScreen()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerContainer.visibility == View.VISIBLE) {
                    if (isPlayerFullscreen) {
                        exitFullscreen()
                    } else {
                        closeNativePlayer()
                    }
                } else if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else {
                    // Check if soft keyboard is currently open or user was typing
                    val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    if (imeVisible || isInputFocusedInWebView || isKeyboardActive) {
                        // User pressed back while keyboard is open:
                        // Just hide the keyboard, DO NOT close modal or navigate away!
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        insetsController.hide(WindowInsetsCompat.Type.ime())
                        isInputFocusedInWebView = false
                        isKeyboardActive = false
                        webView.evaluateJavascript(
                            "(function() { var active = document.activeElement; if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) { active.blur(); } })()",
                            null
                        )
                        return
                    }

                    // Check if active element in WebView is an input/textarea
                    webView.evaluateJavascript(
                        "(function() { " +
                        "  var active = document.activeElement; " +
                        "  if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) { " +
                        "    active.blur(); " +
                        "    return 'blurred'; " +
                        "  } " +
                        "  if (window.handleAppBack && window.handleAppBack()) { return 'handled'; } " +
                        "  if (window.historyStack && window.historyStack.length > 0) { window.goBack(); return 'goback'; } " +
                        "  return 'none'; " +
                        "})()"
                    ) { result ->
                        val cleanResult = result?.replace("\"", "") ?: "none"
                        if (cleanResult == "none") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            }
        })
    }

    private fun enterFullscreen() {
        if (isPlayerFullscreen) return
        isPlayerFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersiveFullScreen()
        val density = resources.displayMetrics.density
        playerTopBar.setPadding(
            (16 * density).toInt(),
            (14 * density).toInt(),
            (16 * density).toInt(),
            (12 * density).toInt()
        )
        playerView.showController()
    }

    private fun exitFullscreen() {
        if (!isPlayerFullscreen) return
        isPlayerFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyImmersiveFullScreen()
        val density = resources.displayMetrics.density
        playerTopBar.setPadding(
            (16 * density).toInt(),
            (14 * density).toInt(),
            (16 * density).toInt(),
            (12 * density).toInt()
        )
        playerView.showController()
    }

    private fun toggleFullscreen(toFullscreen: Boolean? = null) {
        val target = toFullscreen ?: !isPlayerFullscreen
        if (target) {
            enterFullscreen()
        } else {
            exitFullscreen()
        }
    }

    fun applyImmersiveFullScreen() {
        runOnUiThread {
            // Never re-hide bars while the keyboard is open or user is actively typing/autofilling: it can dismiss the IME and drop focus.
            if (isInputFocusedInWebView || isKeyboardActive) return@runOnUiThread
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) return@runOnUiThread
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideSystemBars() {
        applyImmersiveFullScreen()
    }

    private fun showSystemBars() {
        // App-wide immersive full-screen is permanently enforced across all screens
        applyImmersiveFullScreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (playerContainer.visibility == View.VISIBLE) {
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            val density = resources.displayMetrics.density
            if (isLandscape && !isPlayerFullscreen) {
                isPlayerFullscreen = true
                playerTopBar.setPadding(
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
            } else if (!isLandscape && isPlayerFullscreen) {
                isPlayerFullscreen = false
                playerTopBar.setPadding(
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlaybackTicker()
        exoPlayer?.pause()
        try {
            webView.onPause()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing webView: ${e.message}")
        }
    }

    fun checkStoragePermissionAndDownload(req: DownloadRequest) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadRequest = req
                runOnUiThread {
                    showStoragePermissionRationaleDialog(req)
                }
                return
            }
        }
        startDownloadInternal(req)
    }

    private fun showStoragePermissionRationaleDialog(req: DownloadRequest) {
        AlertDialog.Builder(this)
            .setTitle("إذن التخزين لحفظ الحلقات")
            .setMessage("لحفظ حلقة \"${req.episodeTitle}\" في مجلد التنزيلات على هاتفك، يحتاج التطبيق إلى إذن الوصول للتخزين.")
            .setPositiveButton("متابعة") { dialog, _ ->
                dialog.dismiss()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_REQUEST_CODE
                )
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
                pendingDownloadRequest = null
                Toast.makeText(this, "تم إلغاء التنزيل لعدم توفر الإذن", Toast.LENGTH_SHORT).show()
                notifyDownloadError(req.episodeKey, "PERMISSION_DENIED", "تم رفض إذن التخزين")
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("تفعيل إذن التخزين")
            .setMessage("تم رفض إذن التخزين سابقاً. يمكنك تفعيل الإذن يدوياً من إعدادات التطبيق لتتمكن من تنزيل الحلقات إلى هاتفك.")
            .setPositiveButton("إعدادات التطبيق") { dialog, _ ->
                dialog.dismiss()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open app settings", e)
                }
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun startDownloadInternal(req: DownloadRequest) {
        var streamUrl = req.url.trim()
        if (streamUrl.isBlank()) {
            runOnUiThread {
                Toast.makeText(this, "رابط الحلقة غير متوفر", Toast.LENGTH_SHORT).show()
                notifyDownloadError(req.episodeKey, "INVALID_URL", "رابط الحلقة غير متوفر")
            }
            return
        }

        // Support Pixeldrain URLs (e.g. https://pixeldrain.com/u/ID or /file/ID -> /api/file/ID)
        val pixeldrainPattern = Regex("""(?i)^(?:https?://)?(?:www\.)?pixeldrain\.com/(?:u|file|api/file)/([a-zA-Z0-9_-]+).*$""")
        val pdMatch = pixeldrainPattern.find(streamUrl)
        if (pdMatch != null) {
            val fileId = pdMatch.groupValues[1]
            streamUrl = "https://pixeldrain.com/api/file/$fileId"
        }

        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            runOnUiThread {
                Toast.makeText(this, "رابط هذه الحلقة هو بث مباشر (m3u8) ولا يمكن تنزيله كملف فيديو", Toast.LENGTH_LONG).show()
                notifyDownloadError(req.episodeKey, "M3U8_NOT_SUPPORTED", "رابط الحلقة بث مباشر ولا يدعم التنزيل كملف منفصل")
            }
            return
        }

        // Check if already downloading to prevent duplicates
        val existingDownloadId = episodeKeyToDownloadId[req.episodeKey]
        if (existingDownloadId != null && activeDownloads.containsKey(existingDownloadId)) {
            val existingInfo = activeDownloads[existingDownloadId]
            if (existingInfo?.status == DownloadManager.STATUS_RUNNING || existingInfo?.status == DownloadManager.STATUS_PENDING) {
                runOnUiThread {
                    Toast.makeText(this, "جاري تنزيل هذه الحلقة بالفعل (${existingInfo.progress}%)", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(streamUrl)

            // Determine extension
            val urlPath = uri.path ?: ""
            val ext = when {
                urlPath.endsWith(".mp4", ignoreCase = true) -> "mp4"
                urlPath.endsWith(".mkv", ignoreCase = true) -> "mkv"
                urlPath.endsWith(".webm", ignoreCase = true) -> "webm"
                urlPath.endsWith(".ts", ignoreCase = true) -> "ts"
                urlPath.endsWith(".mov", ignoreCase = true) -> "mov"
                else -> "mp4"
            }

            val mimeType = when (ext) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "ts" -> "video/mp2t"
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            }

            // Sanitize file name for filesystem
            val safeAnime = req.animeTitle.replace(Regex("""[\\\\/:*?\"<>|]"""), "_").trim().ifBlank { "Anime" }
            val safeEp = req.episodeTitle.replace(Regex("""[\\\\/:*?\"<>|]"""), "_").trim().ifBlank { "Episode" }
            val fileName = "$safeAnime - $safeEp.$ext"

            val request = DownloadManager.Request(uri).apply {
                setTitle("${req.animeTitle} - ${req.episodeTitle}")
                setDescription("تنزيل حلقة من تطبيق Black Anime")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setMimeType(mimeType)
            }

            val downloadId = downloadManager.enqueue(request)
            val info = DownloadItemInfo(
                downloadId = downloadId,
                episodeKey = req.episodeKey,
                fileName = fileName,
                animeTitle = req.animeTitle,
                episodeTitle = req.episodeTitle,
                status = DownloadManager.STATUS_PENDING,
                progress = 0
            )
            activeDownloads[downloadId] = info
            episodeKeyToDownloadId[req.episodeKey] = downloadId

            runOnUiThread {
                Toast.makeText(this, "بدأ تنزيل: ${req.episodeTitle} في مجلد التنزيلات", Toast.LENGTH_SHORT).show()
                val js = "if(window.onEpisodeDownloadStatusChanged){window.onEpisodeDownloadStatusChanged('${req.episodeKey}', 'STARTED', 0, '$fileName');}"
                webView.evaluateJavascript(js, null)
            }

            startProgressPollingLoop()

        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager failed to enqueue", e)
            runOnUiThread {
                Toast.makeText(this, "فشل بدء التنزيل: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                notifyDownloadError(req.episodeKey, "ENQUEUE_ERROR", e.localizedMessage ?: "فشل بدء التنزيل")
            }
        }
    }

    private fun registerDownloadReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (downloadId != -1L) {
                        handleDownloadCompleted(downloadId)
                    }
                }
            }
        }
        downloadCompleteReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun handleDownloadCompleted(downloadId: Long) {
        val info = activeDownloads[downloadId] ?: return
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor != null && cursor.moveToFirst()) {
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1
            cursor.close()

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                info.status = DownloadManager.STATUS_SUCCESSFUL
                info.progress = 100
                markEpisodeDownloadedInPrefs(info.episodeKey, info.fileName)

                try {
                    val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), info.fileName)
                    if (file.exists()) {
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(file.absolutePath),
                            arrayOf("video/*")
                        ) { path, uri ->
                            Log.d(TAG, "MediaScanner scanned downloaded episode: $path -> $uri")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaScanner error", e)
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ اكتمل تنزيل: ${info.episodeTitle}", Toast.LENGTH_LONG).show()
                    val js = "if(window.onEpisodeDownloadStatusChanged){window.onEpisodeDownloadStatusChanged('${info.episodeKey}', 'COMPLETED', 100, '${info.fileName}');}"
                    webView.evaluateJavascript(js, null)
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                info.status = DownloadManager.STATUS_FAILED
                activeDownloads.remove(downloadId)
                episodeKeyToDownloadId.remove(info.episodeKey)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ تعذر اكتمال تنزيل: ${info.episodeTitle}", Toast.LENGTH_LONG).show()
                    val js = "if(window.onEpisodeDownloadStatusChanged){window.onEpisodeDownloadStatusChanged('${info.episodeKey}', 'FAILED', 0, '${info.fileName}');}"
                    webView.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun startProgressPollingLoop() {
        if (isProgressPollingActive) return
        isProgressPollingActive = true
        lifecycleScope.launch {
            while (isProgressPollingActive) {
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (downloadManager == null || activeDownloads.isEmpty()) {
                    isProgressPollingActive = false
                    break
                }

                var hasActive = false
                for ((id, info) in activeDownloads) {
                    if (info.status == DownloadManager.STATUS_RUNNING || info.status == DownloadManager.STATUS_PENDING) {
                        hasActive = true
                        val query = DownloadManager.Query().setFilterById(id)
                        val cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val bytesSoFar = if (bytesIdx != -1) cursor.getLong(bytesIdx) else 0L
                            val totalBytes = if (totalIdx != -1) cursor.getLong(totalIdx) else 0L
                            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else info.status
                            cursor.close()

                            info.status = status
                            if (totalBytes > 0) {
                                val percent = ((bytesSoFar * 100) / totalBytes).toInt().coerceIn(0, 100)
                                info.progress = percent
                                runOnUiThread {
                                    val js = "if(window.onEpisodeDownloadProgress){window.onEpisodeDownloadProgress('${info.episodeKey}', $percent, 'DOWNLOADING');}"
                                    webView.evaluateJavascript(js, null)
                                }
                            }
                        }
                    }
                }

                if (!hasActive) {
                    isProgressPollingActive = false
                    break
                }

                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun markEpisodeDownloadedInPrefs(episodeKey: String, fileName: String) {
        val prefs = getSharedPreferences("black_anime_downloads", Context.MODE_PRIVATE)
        prefs.edit().putString("file_$episodeKey", fileName).apply()
    }

    fun isEpisodeDownloaded(episodeKey: String): Boolean {
        val prefs = getSharedPreferences("black_anime_downloads", Context.MODE_PRIVATE)
        val fileName = prefs.getString("file_$episodeKey", null) ?: return false
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        val exists = file.exists()
        if (!exists) {
            prefs.edit().remove("file_$episodeKey").apply()
        }
        return exists
    }

    fun getDownloadedEpisodesJson(): String {
        val prefs = getSharedPreferences("black_anime_downloads", Context.MODE_PRIVATE)
        val all = prefs.all
        val json = JSONObject()
        for ((k, v) in all) {
            if (k.startsWith("file_") && v is String) {
                val key = k.removePrefix("file_")
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), v)
                if (file.exists()) {
                    json.put(key, v)
                }
            }
        }
        return json.toString()
    }

    fun openDownloadedVideo(episodeKey: String): Boolean {
        val prefs = getSharedPreferences("black_anime_downloads", Context.MODE_PRIVATE)
        val fileName = prefs.getString("file_$episodeKey", null) ?: return false
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            prefs.edit().remove("file_$episodeKey").apply()
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "تشغيل الحلقة المحملة بواسطة"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open video file", e)
            false
        }
    }

    private fun notifyDownloadError(episodeKey: String, errorCode: String, message: String) {
        val js = "if(window.onEpisodeDownloadStatusChanged){window.onEpisodeDownloadStatusChanged('$episodeKey', 'FAILED', 0, '$errorCode');}"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onDestroy() {
        stopPlaybackTicker()
        isProgressPollingActive = false
        try {
            downloadCompleteReceiver?.let { unregisterReceiver(it) }
            downloadCompleteReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering download receiver: ${e.message}")
        }
        exoPlayer?.release()
        exoPlayer = null
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Native AdMob and Video Player bridge exposed to JavaScript
     */
    class AdMobBridge(private val activity: MainActivity, private val webView: WebView) {
        private val mainHandler = Handler(Looper.getMainLooper())

        // ================= Five-Ads Exemption System State =================
        @Volatile private var fiveAdsState: String = "IDLE"
        @Volatile private var currentFiveAdsAttemptId: String = ""
        @Volatile private var isLoadingFiveAds: Boolean = false
        @Volatile private var isShowingFiveAds: Boolean = false
        private var fiveAdsTimeoutRunnable: Runnable? = null

        // ================= Episode Ad System State =================
        @Volatile private var episodeState: String = "IDLE"
        @Volatile private var currentEpisodeAttemptId: String = ""
        @Volatile private var isLoadingEpisode: Boolean = false
        @Volatile private var isShowingEpisode: Boolean = false
        private var episodeTimeoutRunnable: Runnable? = null
        @Volatile private var currentEpisodeFid: String = ""
        @Volatile private var currentEpisodeIdx: Int = 0

        // Helpers
        private fun isAnyAdShowing(): Boolean =
            isShowingFiveAds || isShowingEpisode || fiveAdsState == "SHOWING" || episodeState == "SHOWING"

        @JavascriptInterface
        fun isAdShowing(): Boolean = isAnyAdShowing()

        @JavascriptInterface
        fun getAdState(): String {
            return when {
                isAnyAdShowing() -> "SHOWING"
                isLoadingFiveAds || isLoadingEpisode || fiveAdsState == "LOADING" || episodeState == "LOADING" -> "LOADING"
                fiveAdsState == "FAILED" || episodeState == "FAILED" -> "FAILED"
                else -> "IDLE"
            }
        }

        @JavascriptInterface
        fun getFiveAdsState(): String = fiveAdsState

        @JavascriptInterface
        fun getEpisodeAdState(): String = episodeState

        // ================= 24-Hour Exemption & Counter Persistence =================
        @JavascriptInterface
        fun isAdExemptionActive(): Boolean {
            val prefs = activity.getSharedPreferences(PREFS_ADMOB, Context.MODE_PRIVATE)
            val exemptUntil = prefs.getLong(KEY_EXEMPT_UNTIL, 0L)
            val now = System.currentTimeMillis()
            val active = exemptUntil > now
            Log.d(TAG, "AdMob: isAdExemptionActive() -> $active (until=$exemptUntil, remaining=${if (active) (exemptUntil - now) else 0}ms)")
            return active
        }

        @JavascriptInterface
        fun getAdExemptionRemainingMs(): Long {
            val prefs = activity.getSharedPreferences(PREFS_ADMOB, Context.MODE_PRIVATE)
            val exemptUntil = prefs.getLong(KEY_EXEMPT_UNTIL, 0L)
            val now = System.currentTimeMillis()
            return if (exemptUntil > now) (exemptUntil - now) else 0L
        }

        @JavascriptInterface
        fun activateAdExemption(): Long {
            val prefs = activity.getSharedPreferences(PREFS_ADMOB, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val expiresAt = now + ONE_DAY_MS
            prefs.edit()
                .putLong(KEY_EXEMPT_UNTIL, expiresAt)
                .putInt(KEY_FIVE_ADS_COUNT, 0)
                .apply()
            Log.d(TAG, "AdMob [FiveAds]: [Exemption Activated] 24-hour exemption activated natively until $expiresAt")
            return expiresAt
        }

        @JavascriptInterface
        fun getFiveAdsCount(): Int {
            if (isAdExemptionActive()) return 5
            val prefs = activity.getSharedPreferences(PREFS_ADMOB, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_FIVE_ADS_COUNT, 0).coerceIn(0, 5)
        }

        @JavascriptInterface
        fun setFiveAdsCount(count: Int) {
            val prefs = activity.getSharedPreferences(PREFS_ADMOB, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_FIVE_ADS_COUNT, count.coerceIn(0, 5)).apply()
            Log.d(TAG, "AdMob [FiveAds]: [Counter] Saved native 5-ads counter: $count/5")
        }

        @JavascriptInterface
        fun resetFiveAdsCount() {
            setFiveAdsCount(0)
        }

        @JavascriptInterface
        fun cancelAdAttempt(attemptId: String) {
            activity.runOnUiThread {
                if (currentFiveAdsAttemptId == attemptId) {
                    cancelFiveAdsAttempt(attemptId)
                }
                if (currentEpisodeAttemptId == attemptId) {
                    cancelEpisodeAdAttempt(attemptId)
                }
            }
        }

        @JavascriptInterface
        fun cancelFiveAdsAttempt(attemptId: String) {
            activity.runOnUiThread {
                if (currentFiveAdsAttemptId == attemptId && fiveAdsState == "LOADING") {
                    Log.d(TAG, "AdMob [FiveAds]: cancelFiveAdsAttempt() for attempt $attemptId")
                    fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    fiveAdsTimeoutRunnable = null
                    isLoadingFiveAds = false
                    fiveAdsState = "IDLE"
                    currentFiveAdsAttemptId = ""
                }
            }
        }

        @JavascriptInterface
        fun cancelEpisodeAdAttempt(attemptId: String) {
            activity.runOnUiThread {
                if (currentEpisodeAttemptId == attemptId && episodeState == "LOADING") {
                    Log.d(TAG, "AdMob [Episode]: cancelEpisodeAdAttempt() for attempt $attemptId")
                    episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    episodeTimeoutRunnable = null
                    isLoadingEpisode = false
                    episodeState = "IDLE"
                    currentEpisodeAttemptId = ""
                    currentEpisodeFid = ""
                    currentEpisodeIdx = 0
                }
            }
        }

        @JavascriptInterface
        fun resetAdState() {
            activity.runOnUiThread {
                if (!isAnyAdShowing()) {
                    fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    fiveAdsTimeoutRunnable = null
                    episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    episodeTimeoutRunnable = null
                    fiveAdsState = "IDLE"
                    episodeState = "IDLE"
                    isLoadingFiveAds = false
                    isLoadingEpisode = false
                    currentFiveAdsAttemptId = ""
                    currentEpisodeAttemptId = ""
                    Log.d(TAG, "AdMob: All ad states reset to IDLE.")
                }
            }
        }

        @JavascriptInterface
        fun isAvailable(): Boolean = true

        @JavascriptInterface
        fun setInputFocused(isFocused: Boolean) {
            activity.setInputFocusedFromJs(isFocused)
        }

        @JavascriptInterface
        fun isNativePlayerAvailable(): Boolean = true

        @JavascriptInterface
        fun playStream(url: String, title: String) {
            activity.playStream(url, title)
        }

        @JavascriptInterface
        fun closePlayer() {
            activity.closeNativePlayer()
        }

        @JavascriptInterface
        fun downloadEpisode(url: String, animeTitle: String, episodeTitle: String, episodeKey: String): String {
            activity.runOnUiThread {
                activity.checkStoragePermissionAndDownload(
                    DownloadRequest(url, animeTitle, episodeTitle, episodeKey)
                )
            }
            return "INITIATED"
        }

        @JavascriptInterface
        fun isEpisodeDownloading(episodeKey: String): Boolean {
            val id = activity.episodeKeyToDownloadId[episodeKey] ?: return false
            val info = activity.activeDownloads[id] ?: return false
            return info.status == DownloadManager.STATUS_RUNNING || info.status == DownloadManager.STATUS_PENDING
        }

        @JavascriptInterface
        fun getEpisodeDownloadProgress(episodeKey: String): Int {
            val id = activity.episodeKeyToDownloadId[episodeKey] ?: return 0
            val info = activity.activeDownloads[id] ?: return 0
            return info.progress
        }

        @JavascriptInterface
        fun isEpisodeDownloaded(episodeKey: String): Boolean {
            return activity.isEpisodeDownloaded(episodeKey)
        }

        @JavascriptInterface
        fun getDownloadedEpisodesList(): String {
            return activity.getDownloadedEpisodesJson()
        }

        @JavascriptInterface
        fun openDownloadedVideo(episodeKey: String): Boolean {
            return activity.openDownloadedVideo(episodeKey)
        }

        @JavascriptInterface
        fun getSavedUserRole(): String {
            val prefs = activity.getSharedPreferences("app_auth_prefs", Activity.MODE_PRIVATE)
            return prefs.getString("user_role", "") ?: ""
        }

        @JavascriptInterface
        fun saveUserRole(role: String) {
            val prefs = activity.getSharedPreferences("app_auth_prefs", Activity.MODE_PRIVATE)
            val normalized = when (role.trim().uppercase()) {
                "ADMIN" -> "ADMIN"
                "ASSISTANT" -> "ASSISTANT"
                else -> "USER"
            }
            prefs.edit().putString("user_role", normalized).remove("admin_secret_token").apply()
            try {
                if (normalized == "ADMIN") {
                    FirebaseMessaging.getInstance().subscribeToTopic("admin_alerts")
                } else {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("admin_alerts")
                }
                MyFirebaseMessagingService.syncDeviceRegistrationInFirestore(
                    activity,
                    MyFirebaseMessagingService.getSavedToken(activity)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing role: ${e.message}")
            }
        }

        @JavascriptInterface
        fun verifyAdminSession(codeOrSession: String): Boolean {
            // Admin authorization is enforced by Firebase custom claims and Firestore rules.
            // A value stored in SharedPreferences or supplied by JavaScript is not proof.
            return false
        }

        @JavascriptInterface
        fun getAdminAuthKey(): String {
            // Never expose a hard-coded admin secret to WebView JavaScript.
            return ""
        }

        @JavascriptInterface
        fun clearUserRole() {
            val prefs = activity.getSharedPreferences("app_auth_prefs", Activity.MODE_PRIVATE)
            prefs.edit().remove("user_role").remove("admin_secret_token").apply()
            try {
                FirebaseMessaging.getInstance().unsubscribeFromTopic("admin_alerts")
                MyFirebaseMessagingService.syncDeviceRegistrationInFirestore(
                    activity,
                    MyFirebaseMessagingService.getSavedToken(activity)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error unsubscribing admin_alerts: ${e.message}")
            }
        }

        @JavascriptInterface
        fun openExternalUrl(rawUrl: String): Boolean {
            if (rawUrl.isBlank()) return false
            var url = rawUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "https://$url"
            }

            return try {
                val uri = Uri.parse(url)
                val isYouTube = url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)

                activity.runOnUiThread {
                    try {
                        if (isYouTube) {
                            // Try opening with YouTube app first
                            val youtubeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.youtube")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                activity.startActivity(youtubeIntent)
                                Log.d(TAG, "External Link: Successfully opened YouTube app for URL: $url")
                                return@runOnUiThread
                            } catch (e: Exception) {
                                Log.d(TAG, "External Link: YouTube app not available, falling back to browser.")
                            }
                        }

                        // Open with default browser or appropriate app on device
                        val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(genericIntent)
                        Log.d(TAG, "External Link: Successfully launched intent for URL: $url")
                    } catch (e: Exception) {
                        Log.e(TAG, "External Link: Error launching intent: ${e.message}", e)
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "External Link: Failed to parse URL: $rawUrl", e)
                false
            }
        }

        @JavascriptInterface
        fun getFcmToken(): String {
            return MyFirebaseMessagingService.getSavedToken(activity)
        }

        @JavascriptInterface
        fun copyFcmTokenToClipboard() {
            activity.runOnUiThread {
                val token = MyFirebaseMessagingService.getSavedToken(activity)
                if (token.isNotBlank()) {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("FCM Token", token)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(activity, "تم نسخ رمز FCM للجهاز بنجاح", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "جاري تهيئة رمز FCM، يرجى المحاولة بعد لحظات", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            activity.runOnUiThread {
                activity.requestNotificationPermission()
            }
        }

        @JavascriptInterface
        fun isNotificationsEnabled(): Boolean {
            return MyFirebaseMessagingService.isAppNotificationsEnabled(activity) && activity.isSystemNotificationsAllowed()
        }

        @JavascriptInterface
        fun isAppNotificationsPrefEnabled(): Boolean {
            return MyFirebaseMessagingService.isAppNotificationsEnabled(activity)
        }

        @JavascriptInterface
        fun isSystemNotificationsGranted(): Boolean {
            return activity.isSystemNotificationsAllowed()
        }

        @JavascriptInterface
        fun setNotificationsEnabled(enabled: Boolean): Boolean {
            MyFirebaseMessagingService.setAppNotificationsEnabled(activity, enabled)
            if (enabled && !activity.isSystemNotificationsAllowed()) {
                activity.runOnUiThread {
                    activity.requestNotificationPermission()
                }
            }
            return MyFirebaseMessagingService.isAppNotificationsEnabled(activity)
        }

        @JavascriptInterface
        fun openSystemNotificationSettings() {
            activity.runOnUiThread {
                try {
                    val intent = Intent().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                        } else {
                            action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            data = Uri.fromParts("package", activity.packageName, null)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening notification settings: ${e.message}", e)
                }
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                pInfo.versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
        }

        @JavascriptInterface
        fun getAppThemePref(): String {
            val prefs = activity.getSharedPreferences("app_settings_prefs", Activity.MODE_PRIVATE)
            return prefs.getString("app_theme", "dark") ?: "dark"
        }

        @JavascriptInterface
        fun saveAppThemePref(theme: String) {
            val prefs = activity.getSharedPreferences("app_settings_prefs", Activity.MODE_PRIVATE)
            prefs.edit().putString("app_theme", theme).apply()
        }

        @JavascriptInterface
        fun getAppLangPref(): String {
            val prefs = activity.getSharedPreferences("app_settings_prefs", Activity.MODE_PRIVATE)
            return prefs.getString("app_lang", "ar") ?: "ar"
        }

        @JavascriptInterface
        fun saveAppLangPref(lang: String) {
            val prefs = activity.getSharedPreferences("app_settings_prefs", Activity.MODE_PRIVATE)
            prefs.edit().putString("app_lang", lang).apply()
        }

        @JavascriptInterface
        fun shareApplicationApk(fallbackWebsiteUrl: String, chooserTitle: String): String {
            return try {
                val appInfo = activity.applicationInfo
                val sourceApk = java.io.File(appInfo.sourceDir)
                if (sourceApk.exists() && sourceApk.canRead()) {
                    val shareDir = java.io.File(activity.cacheDir, "shared_apk")
                    if (!shareDir.exists()) {
                        shareDir.mkdirs()
                    }
                    val destApk = java.io.File(shareDir, "BLACK_ANIME.apk")
                    sourceApk.inputStream().use { input ->
                        destApk.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val apkUri = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        destApk
                    )
                    activity.runOnUiThread {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_STREAM, apkUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri("BLACK ANIME APK", apkUri)
                            }
                            val chooser = Intent.createChooser(shareIntent, chooserTitle.ifBlank { "BLACK ANIME" }).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(chooser)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching APK share chooser: ${e.message}", e)
                        }
                    }
                    "APK_SHARED"
                } else if (fallbackWebsiteUrl.isNotBlank() && (fallbackWebsiteUrl.startsWith("http://") || fallbackWebsiteUrl.startsWith("https://"))) {
                    activity.runOnUiThread {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "BLACK ANIME")
                                putExtra(Intent.EXTRA_TEXT, "BLACK ANIME\n$fallbackWebsiteUrl")
                            }
                            val chooser = Intent.createChooser(shareIntent, chooserTitle.ifBlank { "BLACK ANIME" }).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            activity.startActivity(chooser)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching link share chooser: ${e.message}", e)
                        }
                    }
                    "LINK_SHARED"
                } else {
                    "UNAVAILABLE"
                }
            } catch (e: Exception) {
                Log.e(TAG, "shareApplicationApk failed: ${e.message}", e)
                if (fallbackWebsiteUrl.isNotBlank() && (fallbackWebsiteUrl.startsWith("http://") || fallbackWebsiteUrl.startsWith("https://"))) {
                    activity.runOnUiThread {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "BLACK ANIME")
                                putExtra(Intent.EXTRA_TEXT, "BLACK ANIME\n$fallbackWebsiteUrl")
                            }
                            val chooser = Intent.createChooser(shareIntent, chooserTitle.ifBlank { "BLACK ANIME" }).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            activity.startActivity(chooser)
                        } catch (ex: Exception) {
                            Log.e(TAG, "Fallback link share failed: ${ex.message}", ex)
                        }
                    }
                    "LINK_SHARED"
                } else {
                    "UNAVAILABLE"
                }
            }
        }

        @JavascriptInterface
        fun getDeviceId(): String {
            return MyFirebaseMessagingService.getDeviceId(activity)
        }

        @JavascriptInterface
        fun markNotificationDelivered(notifId: String) {
            MyFirebaseMessagingService.markNotificationDelivered(activity.applicationContext, notifId)
        }

        @JavascriptInterface
        fun triggerConfiguredNotification() {
            activity.runOnUiThread {
                NotificationConfig.sendConfiguredNotification(activity)
            }
        }

        @JavascriptInterface
        fun triggerCustomNotification(
            title: String,
            message: String,
            page: String,
            contentId: String,
            episode: String,
            imageUrl: String,
            action: String,
            contentTitle: String
        ) {
            activity.runOnUiThread {
                NotificationConfig.sendCustomNotification(
                    context = activity,
                    title = title,
                    message = message,
                    page = page,
                    contentId = contentId,
                    episode = episode,
                    url = "",
                    action = action,
                    imageUrl = imageUrl,
                    contentTitle = contentTitle
                )
            }
        }

        @JavascriptInterface
        fun triggerCustomNotificationWithId(
            title: String,
            message: String,
            page: String,
            contentId: String,
            episode: String,
            imageUrl: String,
            action: String,
            contentTitle: String,
            notifId: String
        ) {
            activity.runOnUiThread {
                NotificationConfig.sendCustomNotification(
                    context = activity,
                    title = title,
                    message = message,
                    page = page,
                    contentId = contentId,
                    episode = episode,
                    url = "",
                    action = action,
                    imageUrl = imageUrl,
                    contentTitle = contentTitle,
                    notifId = notifId
                )
            }
        }

        // ================= Five-Ads System Implementation =================
        @JavascriptInterface
        fun loadAndShowFiveAds(timeoutMs: Long, attemptId: String) {
            val targetUnitId = DEFAULT_REWARDED_AD_UNIT_ID
            activity.runOnUiThread {
                if (isAdExemptionActive()) {
                    Log.d(TAG, "AdMob [FiveAds]: User already has active 24h exemption. No ad required.")
                    notifyFiveAdsResult(true, "already_exempt", attemptId, 5, true)
                    return@runOnUiThread
                }
                if (isAnyAdShowing()) {
                    Log.w(TAG, "AdMob [FiveAds]: loadAndShowFiveAds rejected - another ad is currently SHOWING.")
                    return@runOnUiThread
                }
                if (isLoadingFiveAds || fiveAdsState == "LOADING") {
                    Log.w(TAG, "AdMob [FiveAds]: Request rejected - another five-ads request is already LOADING.")
                    return@runOnUiThread
                }

                currentFiveAdsAttemptId = attemptId
                fiveAdsState = "LOADING"
                isLoadingFiveAds = true

                Log.d(TAG, "AdMob [FiveAds]: [Load Start] Attempt $attemptId with Unit $targetUnitId (timeout=${timeoutMs}ms)")

                fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val timer = Runnable {
                    activity.runOnUiThread {
                        if (isShowingFiveAds || fiveAdsState == "SHOWING") {
                            Log.d(TAG, "AdMob [FiveAds]: Timeout ignored because ad is currently SHOWING for attempt $attemptId")
                            return@runOnUiThread
                        }
                        if (fiveAdsState == "LOADING" && currentFiveAdsAttemptId == attemptId) {
                            fiveAdsState = "FAILED"
                            isLoadingFiveAds = false
                            currentFiveAdsAttemptId = ""
                            Log.w(TAG, "AdMob [FiveAds]: [Load Timeout] Timed out after ${timeoutMs}ms for attempt $attemptId")
                            notifyFiveAdsResult(false, "timeout", attemptId, getFiveAdsCount(), false)
                        }
                    }
                }
                fiveAdsTimeoutRunnable = timer
                mainHandler.postDelayed(timer, timeoutMs)

                val adRequest = AdRequest.Builder().build()
                RewardedAd.load(activity, targetUnitId, adRequest, object : RewardedAdLoadCallback() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        activity.runOnUiThread {
                            if (isShowingFiveAds || fiveAdsState == "SHOWING") return@runOnUiThread
                            if (currentFiveAdsAttemptId == attemptId && fiveAdsState == "LOADING") {
                                fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                fiveAdsTimeoutRunnable = null
                                fiveAdsState = "FAILED"
                                isLoadingFiveAds = false
                                currentFiveAdsAttemptId = ""
                                Log.e(
                                    TAG,
                                    "AdMob [FiveAds]: [Load Failed] Attempt $attemptId - Code: ${loadAdError.code}, Message: ${loadAdError.message}, Domain: ${loadAdError.domain}, ResponseInfo: ${loadAdError.responseInfo}"
                                )
                                notifyFiveAdsResult(false, "load_failed", attemptId, getFiveAdsCount(), false)
                            }
                        }
                    }

                    override fun onAdLoaded(rewardedAd: RewardedAd) {
                        activity.runOnUiThread {
                            if (currentFiveAdsAttemptId != attemptId || fiveAdsState != "LOADING" || isAnyAdShowing()) {
                                Log.w(TAG, "AdMob [FiveAds]: Ad loaded late or state changed for attempt $attemptId (state=$fiveAdsState, isShowing=$isShowingFiveAds)")
                                return@runOnUiThread
                            }
                            fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            fiveAdsTimeoutRunnable = null
                            Log.d(TAG, "AdMob [FiveAds]: [Load Success] Ad loaded successfully for attempt $attemptId. Showing now...")
                            showLoadedFiveAds(rewardedAd, attemptId)
                        }
                    }
                })
            }
        }

        private fun showLoadedFiveAds(rewardedAd: RewardedAd, attemptId: String) {
            fiveAdsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            fiveAdsTimeoutRunnable = null
            isLoadingFiveAds = false
            isShowingFiveAds = true
            fiveAdsState = "SHOWING"

            var userEarnedReward = false
            var isTerminalStateHandled = false

            rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    activity.runOnUiThread {
                        Log.d(TAG, "AdMob [FiveAds]: [Show] onAdShowedFullScreenContent() for attempt $attemptId")
                        isShowingFiveAds = true
                        fiveAdsState = "SHOWING"
                        notifyFiveAdsAdShowed(attemptId)
                    }
                }

                override fun onAdDismissedFullScreenContent() {
                    activity.runOnUiThread {
                        Log.d(TAG, "AdMob [FiveAds]: [Dismiss] Ad dismissed by user for attempt $attemptId (rewardEarned=$userEarnedReward)")
                        isShowingFiveAds = false
                        fiveAdsState = "DISMISSED"
                        activity.applyImmersiveFullScreen()

                        if (!isTerminalStateHandled && currentFiveAdsAttemptId == attemptId) {
                            isTerminalStateHandled = true
                            currentFiveAdsAttemptId = ""
                            if (userEarnedReward) {
                                val newCount = getFiveAdsCount() + 1
                                Log.d(TAG, "AdMob [FiveAds]: [Counter] Updating 5-ads counter: $newCount/5")
                                if (newCount >= 5) {
                                    val expiry = activateAdExemption()
                                    Log.d(TAG, "AdMob [FiveAds]: [Exemption Activated] 5th reward confirmed! 24h Exemption active until $expiry")
                                    notifyFiveAdsResult(true, "watched", attemptId, 5, true)
                                } else {
                                    setFiveAdsCount(newCount)
                                    notifyFiveAdsResult(true, "watched", attemptId, newCount, false)
                                }
                            } else {
                                Log.w(TAG, "AdMob [FiveAds]: [Dismissed Early] Ad dismissed without reward. Counter not incremented.")
                                notifyFiveAdsResult(false, "dismissed_early", attemptId, getFiveAdsCount(), false)
                            }
                        }
                    }
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    activity.runOnUiThread {
                        Log.e(
                            TAG,
                            "AdMob [FiveAds]: [Show Failed] Attempt $attemptId - Code: ${adError.code}, Message: ${adError.message}"
                        )
                        isShowingFiveAds = false
                        fiveAdsState = "FAILED"
                        activity.applyImmersiveFullScreen()

                        if (!isTerminalStateHandled && currentFiveAdsAttemptId == attemptId) {
                            isTerminalStateHandled = true
                            currentFiveAdsAttemptId = ""
                            notifyFiveAdsResult(false, "failed_to_show", attemptId, getFiveAdsCount(), false)
                        }
                    }
                }
            }

            try {
                rewardedAd.show(activity) { rewardItem ->
                    userEarnedReward = true
                    Log.d(
                        TAG,
                        "AdMob [FiveAds]: [Reward Earned] User earned reward for attempt $attemptId - Type: ${rewardItem.type}, Amount: ${rewardItem.amount}"
                    )
                    notifyFiveAdsRewardEarned(attemptId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AdMob [FiveAds]: Exception while showing rewarded ad for attempt $attemptId: ${e.message}", e)
                isShowingFiveAds = false
                isLoadingFiveAds = false
                fiveAdsState = "FAILED"
                if (!isTerminalStateHandled && currentFiveAdsAttemptId == attemptId) {
                    isTerminalStateHandled = true
                    currentFiveAdsAttemptId = ""
                    notifyFiveAdsResult(false, "exception", attemptId, getFiveAdsCount(), false)
                }
            }
        }

        // ================= Episode Ad System Implementation =================
        @JavascriptInterface
        fun loadAndShowEpisodeAd(timeoutMs: Long, attemptId: String, episodeFid: String, episodeIdx: Int) {
            val targetUnitId = EPISODE_REWARDED_AD_UNIT_ID
            activity.runOnUiThread {
                if (isAdExemptionActive()) {
                    Log.d(TAG, "AdMob [Episode]: 24-hour exemption is active. Permitting episode watch directly without ad.")
                    notifyEpisodeResult(true, "exempt", attemptId, episodeFid, episodeIdx)
                    return@runOnUiThread
                }
                if (isAnyAdShowing()) {
                    Log.w(TAG, "AdMob [Episode]: loadAndShowEpisodeAd rejected - another ad is currently SHOWING.")
                    return@runOnUiThread
                }
                if (isLoadingEpisode || episodeState == "LOADING") {
                    Log.w(TAG, "AdMob [Episode]: Request rejected - another episode ad request is already LOADING.")
                    return@runOnUiThread
                }

                currentEpisodeAttemptId = attemptId
                currentEpisodeFid = episodeFid
                currentEpisodeIdx = episodeIdx
                episodeState = "LOADING"
                isLoadingEpisode = true

                Log.d(
                    TAG,
                    "AdMob [Episode]: [Load Start] Attempt $attemptId for episode $episodeFid #$episodeIdx with Unit $targetUnitId (timeout=${timeoutMs}ms)"
                )

                episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val timer = Runnable {
                    activity.runOnUiThread {
                        if (isShowingEpisode || episodeState == "SHOWING") {
                            Log.d(TAG, "AdMob [Episode]: Timeout ignored because episode ad is currently SHOWING for attempt $attemptId")
                            return@runOnUiThread
                        }
                        if (episodeState == "LOADING" && currentEpisodeAttemptId == attemptId) {
                            episodeState = "FAILED"
                            isLoadingEpisode = false
                            currentEpisodeAttemptId = ""
                            Log.w(TAG, "AdMob [Episode]: [Load Timeout] Episode ad search timed out after ${timeoutMs}ms for attempt $attemptId")
                            notifyEpisodeResult(false, "timeout", attemptId, episodeFid, episodeIdx)
                        }
                    }
                }
                episodeTimeoutRunnable = timer
                mainHandler.postDelayed(timer, timeoutMs)

                val adRequest = AdRequest.Builder().build()
                RewardedAd.load(activity, targetUnitId, adRequest, object : RewardedAdLoadCallback() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        activity.runOnUiThread {
                            if (isShowingEpisode || episodeState == "SHOWING") return@runOnUiThread
                            if (currentEpisodeAttemptId == attemptId && episodeState == "LOADING") {
                                episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                                episodeTimeoutRunnable = null
                                episodeState = "FAILED"
                                isLoadingEpisode = false
                                currentEpisodeAttemptId = ""
                                Log.e(
                                    TAG,
                                    "AdMob [Episode]: [Load Failed] Attempt $attemptId - Code: ${loadAdError.code}, Message: ${loadAdError.message}, Domain: ${loadAdError.domain}, ResponseInfo: ${loadAdError.responseInfo}"
                                )
                                notifyEpisodeResult(false, "load_failed", attemptId, episodeFid, episodeIdx)
                            }
                        }
                    }

                    override fun onAdLoaded(rewardedAd: RewardedAd) {
                        activity.runOnUiThread {
                            if (currentEpisodeAttemptId != attemptId || episodeState != "LOADING" || isAnyAdShowing()) {
                                Log.w(TAG, "AdMob [Episode]: Ad loaded late or state changed for attempt $attemptId (state=$episodeState, isShowing=$isShowingEpisode)")
                                return@runOnUiThread
                            }
                            episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            episodeTimeoutRunnable = null
                            Log.d(TAG, "AdMob [Episode]: [Load Success] Episode ad loaded successfully for attempt $attemptId. Showing now...")
                            showLoadedEpisodeAd(rewardedAd, attemptId, episodeFid, episodeIdx)
                        }
                    }
                })
            }
        }

        private fun showLoadedEpisodeAd(rewardedAd: RewardedAd, attemptId: String, episodeFid: String, episodeIdx: Int) {
            episodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            episodeTimeoutRunnable = null
            isLoadingEpisode = false
            isShowingEpisode = true
            episodeState = "SHOWING"

            var userEarnedReward = false
            var isTerminalStateHandled = false

            rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    activity.runOnUiThread {
                        Log.d(TAG, "AdMob [Episode]: [Show] onAdShowedFullScreenContent() - Episode ad is now SHOWING for attempt $attemptId")
                        isShowingEpisode = true
                        episodeState = "SHOWING"
                        notifyEpisodeAdShowed(attemptId)
                    }
                }

                override fun onAdDismissedFullScreenContent() {
                    activity.runOnUiThread {
                        Log.d(TAG, "AdMob [Episode]: [Dismiss] Episode ad dismissed by user for attempt $attemptId (rewardEarned=$userEarnedReward)")
                        isShowingEpisode = false
                        episodeState = "DISMISSED"
                        activity.applyImmersiveFullScreen()

                        if (!isTerminalStateHandled && currentEpisodeAttemptId == attemptId) {
                            isTerminalStateHandled = true
                            currentEpisodeAttemptId = ""
                            if (userEarnedReward) {
                                Log.d(TAG, "AdMob [Episode]: [Watch Permitted] Reward verified. Permitting episode watch ($episodeFid #$episodeIdx)!")
                                notifyEpisodeResult(true, "watched", attemptId, episodeFid, episodeIdx)
                            } else {
                                Log.w(TAG, "AdMob [Episode]: [Watch Blocked] Dismissed early without reward. Denying episode watch.")
                                notifyEpisodeResult(false, "dismissed_early", attemptId, episodeFid, episodeIdx)
                            }
                        }
                    }
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    activity.runOnUiThread {
                        Log.e(
                            TAG,
                            "AdMob [Episode]: [Show Failed] Attempt $attemptId - Code: ${adError.code}, Message: ${adError.message}"
                        )
                        isShowingEpisode = false
                        episodeState = "FAILED"
                        activity.applyImmersiveFullScreen()

                        if (!isTerminalStateHandled && currentEpisodeAttemptId == attemptId) {
                            isTerminalStateHandled = true
                            currentEpisodeAttemptId = ""
                            notifyEpisodeResult(false, "failed_to_show", attemptId, episodeFid, episodeIdx)
                        }
                    }
                }
            }

            try {
                rewardedAd.show(activity) { rewardItem ->
                    userEarnedReward = true
                    Log.d(
                        TAG,
                        "AdMob [Episode]: [Reward Earned] User earned reward for episode ad attempt $attemptId ($episodeFid #$episodeIdx) - Type: ${rewardItem.type}, Amount: ${rewardItem.amount}"
                    )
                    notifyEpisodeRewardEarned(attemptId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AdMob [Episode]: Exception while showing episode ad for attempt $attemptId: ${e.message}", e)
                isShowingEpisode = false
                isLoadingEpisode = false
                episodeState = "FAILED"
                if (!isTerminalStateHandled && currentEpisodeAttemptId == attemptId) {
                    isTerminalStateHandled = true
                    currentEpisodeAttemptId = ""
                    notifyEpisodeResult(false, "exception", attemptId, episodeFid, episodeIdx)
                }
            }
        }

        // ================= Backward Compatibility Dispatcher =================
        @JavascriptInterface
        fun loadAndShowRewardedAd(adUnitId: String, timeoutMs: Long) {
            loadAndShowRewardedAd(adUnitId, timeoutMs, System.currentTimeMillis().toString())
        }

        @JavascriptInterface
        fun loadAndShowRewardedAd(adUnitId: String, timeoutMs: Long, attemptId: String) {
            if (adUnitId.contains("9116970862") || adUnitId == EPISODE_REWARDED_AD_UNIT_ID) {
                loadAndShowEpisodeAd(timeoutMs, attemptId, "", 0)
            } else {
                loadAndShowFiveAds(timeoutMs, attemptId)
            }
        }

        // ================= Notification Callbacks to WebView =================
        private fun notifyFiveAdsAdShowed(attemptId: String) {
            val script = "if(window.onFiveAdsShowed){window.onFiveAdsShowed('$attemptId');}if(window.onAdMobAdShowed){window.onAdMobAdShowed('$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        private fun notifyFiveAdsRewardEarned(attemptId: String) {
            val script = "if(window.onFiveAdsRewardEarned){window.onFiveAdsRewardEarned('$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        private fun notifyFiveAdsResult(success: Boolean, reason: String, attemptId: String, newCount: Int, isExempt: Boolean) {
            val script = "if(window.onFiveAdsResult){window.onFiveAdsResult($success,'$reason','$attemptId',$newCount,$isExempt);}if(window.onAdMobRewardResult){window.onAdMobRewardResult($success,'$reason','$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        private fun notifyEpisodeAdShowed(attemptId: String) {
            val script = "if(window.onEpisodeAdShowed){window.onEpisodeAdShowed('$attemptId');}if(window.onAdMobAdShowed){window.onAdMobAdShowed('$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        private fun notifyEpisodeRewardEarned(attemptId: String) {
            val script = "if(window.onEpisodeRewardEarned){window.onEpisodeRewardEarned('$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        private fun notifyEpisodeResult(success: Boolean, reason: String, attemptId: String, episodeFid: String, episodeIdx: Int) {
            val escapedFid = JSONObject.quote(episodeFid)
            val script = "if(window.onEpisodeResult){window.onEpisodeResult($success,'$reason','$attemptId',$escapedFid,$episodeIdx);}if(window.onAdMobRewardResult){window.onAdMobRewardResult($success,'$reason','$attemptId');}"
            webView.evaluateJavascript(script, null)
        }

        @JavascriptInterface
        fun signInWithGoogle() {
            activity.runOnUiThread {
                activity.launchNativeGoogleSignIn()
            }
        }
    }

    fun launchNativeGoogleSignIn() {
        val serverClientId = "595903053058-om6vigelenhg8374vr2ou28ksvlaanfi.apps.googleusercontent.com"
        val credentialManager = CredentialManager.create(this)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Native Google Sign-In: Requesting credentials via CredentialManager with serverClientId=$serverClientId")
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity
                )
                val credential = result.credential
                Log.d(TAG, "Native Google Sign-In: Received credential class=${credential.javaClass.name}")

                val idToken: String? = when {
                    credential is GoogleIdTokenCredential -> {
                        Log.d(TAG, "Native Google Sign-In: Direct GoogleIdTokenCredential for user=${credential.id}")
                        credential.idToken
                    }
                    credential is CustomCredential &&
                        (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                         credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL) -> {
                        try {
                            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                            Log.d(TAG, "Native Google Sign-In: Extracted GoogleIdTokenCredential from Bundle for user=${googleIdTokenCredential.id}")
                            googleIdTokenCredential.idToken
                        } catch (e: Exception) {
                            Log.e(TAG, "Native Google Sign-In: Failed to parse GoogleIdTokenCredential from Bundle: ${e.message}", e)
                            null
                        }
                    }
                    else -> {
                        val credType = (credential as? CustomCredential)?.type ?: "unknown"
                        Log.e(TAG, "Native Google Sign-In: Unsupported credential class=${credential.javaClass.name}, type=$credType")
                        null
                    }
                }

                if (!idToken.isNullOrBlank()) {
                    Log.d(TAG, "Native Google Sign-In: ID Token obtained successfully (length=${idToken.length}). Dispatching to WebView...")
                    runOnUiThread {
                        val jsCode = """
                            (function() {
                                try {
                                    if (typeof window.onNativeGoogleSignInSuccess === 'function') {
                                        window.onNativeGoogleSignInSuccess(${JSONObject.quote(idToken)});
                                        return 'ok';
                                    } else {
                                        console.error('onNativeGoogleSignInSuccess is not defined on window');
                                        return 'not_found';
                                    }
                                } catch (err) {
                                    console.error('Error invoking onNativeGoogleSignInSuccess:', err);
                                    return 'error: ' + err.message;
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(jsCode) { evalRes ->
                            Log.d(TAG, "Native Google Sign-In: onNativeGoogleSignInSuccess evaluateJavascript response: $evalRes")
                        }
                    }
                } else {
                    val failureMsg = "تعذر استخراج رمز التحقق من حساب Google (ID Token)."
                    Log.e(TAG, "Native Google Sign-In: $failureMsg")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, failureMsg, Toast.LENGTH_SHORT).show()
                        val jsCode = """
                            (function() {
                                try {
                                    if (typeof window.onNativeGoogleSignInFailure === 'function') {
                                        window.onNativeGoogleSignInFailure(${JSONObject.quote(failureMsg)});
                                        return 'ok';
                                    }
                                } catch (err) {
                                    console.error('Error invoking onNativeGoogleSignInFailure:', err);
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(jsCode, null)
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Native Google Sign-In: User cancelled the sign-in selector: ${e.message}")
                runOnUiThread {
                    val jsCode = """
                        (function() {
                            try {
                                if (typeof window.onNativeGoogleSignInFailure === 'function') {
                                    window.onNativeGoogleSignInFailure('تم إلغاء تسجيل الدخول');
                                }
                            } catch (err) {
                                console.error('Error invoking onNativeGoogleSignInFailure (cancel):', err);
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(jsCode, null)
                }
            } catch (e: Throwable) {
                val exceptionName = e.javaClass.simpleName
                val rawMessage = e.message ?: "Unknown error"
                Log.e(TAG, "Native Google Sign-In failed [$exceptionName]: $rawMessage", e)

                val userMessage = formatGoogleSignInErrorMessage(e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, userMessage, Toast.LENGTH_LONG).show()
                    val jsCode = """
                        (function() {
                            try {
                                if (typeof window.onNativeGoogleSignInFailure === 'function') {
                                    window.onNativeGoogleSignInFailure(${JSONObject.quote(userMessage)});
                                    return 'ok';
                                } else {
                                    console.error('onNativeGoogleSignInFailure is not defined on window');
                                    return 'not_found';
                                }
                            } catch (err) {
                                console.error('Error invoking onNativeGoogleSignInFailure:', err);
                                return 'error: ' + err.message;
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(jsCode) { evalRes ->
                        Log.d(TAG, "Native Google Sign-In: onNativeGoogleSignInFailure evaluateJavascript response: $evalRes")
                    }
                }
            }
        }
    }

    private fun formatGoogleSignInErrorMessage(e: Throwable): String {
        val rawMsg = e.message ?: ""
        val className = e.javaClass.simpleName
        return when {
            rawMsg.contains("10:") || rawMsg.contains("DEVELOPER_ERROR", ignoreCase = true) ->
                "خطأ في مطابقة شهادة التطبيق (Developer Error 10): يلزم إضافة بصمة SHA-1 الخاصة بالتطبيق (9C:BB:DC:B9:FC:37:CA:66:1C:49:45:1D:87:E2:8D:2C:CE:C7:6D:CE) واسم الحزمة (com.aistudio.iptvlive.qkxmr) في إعدادات مشروع Firebase Console."
            rawMsg.contains("16:") || rawMsg.contains("Cannot find a matching credential", ignoreCase = true) ->
                "تعذر العثور على حساب Google متطابق، أو أن Web Client ID غير متطابق مع هذا المشروع."
            rawMsg.contains("7:") || rawMsg.contains("NETWORK", ignoreCase = true) ->
                "خطأ في الاتصال بالإنترنت أثناء التواصل مع خدمات Google Play."
            rawMsg.contains("12500") ->
                "فشل تسجيل الدخول من خلال Google Play Services (رمز 12500)."
            e is GetCredentialProviderConfigurationException ->
                "مزود المصادقة غير مهيأ بشكل صحيح على الجهاز (ProviderConfigurationException)."
            e is NoCredentialException ->
                "لا توجد بيانات اعتماد متاحة لحساب Google في هذا الجهاز."
            else ->
                "فشل تسجيل الدخول عبر Google ($className): ${e.localizedMessage ?: "حدث خطأ غير متوقع"}"
        }
    }
}
