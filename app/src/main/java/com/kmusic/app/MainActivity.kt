package com.kmusic.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$HOST/assets/www/index.html"

        @Volatile
        var current: MainActivity? = null

        /** A song the car screen asked for while the app was closed. */
        @Volatile var pendingPlay: String? = null
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bridge: NativeBridge
    lateinit var store: MusicStore
    private lateinit var insetsController: WindowInsetsControllerCompat
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var dark = true

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileCallback
            fileCallback = null
            if (cb != null) {
                val uris = if (result.resultCode == RESULT_OK)
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                else null
                cb.onReceiveValue(uris)
            }
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                runJs("window.__kmImported&&window.__kmImported(0,0)")
            } else {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
                Thread {
                    var ok = 0
                    var bad = 0
                    for (u in uris) {
                        if (store.importUri(u) != null) ok++ else bad++
                    }
                    runJs("window.__kmImported&&window.__kmImported($ok,$bad)")
                }.start()
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bridge.onMicPermissionResult(granted)
        }

    private var askedByUser = false
    @Volatile private var scanning = false
    private var lastScan = 0L

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scanPhone(!askedByUser)
            } else if (askedByUser) {
                openAppSettings()
                runJs("window.__kmImported&&window.__kmImported(0,0)")
            }
            askedByUser = false
        }

    private val videoPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) openAppSettings()
            runJs("window.__kmPhoneVideos&&window.__kmPhoneVideos()")
        }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "Turn on Photos and videos / Music access", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { }
    }

    private fun videoPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasVideoAccess() =
        ContextCompat.checkSelfPermission(this, videoPerm()) == PackageManager.PERMISSION_GRANTED

    fun requestVideoAccess() {
        if (hasVideoAccess()) runJs("window.__kmPhoneVideos&&window.__kmPhoneVideos()")
        else videoPermission.launch(videoPerm())
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ensureAudioPermission() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        store = MusicStore.get(applicationContext)
        Downloads.init(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        insetsController = WindowCompat.getInsetsController(window, root)

        // Keep the web page inside the safe area (status bar, gesture bar, notch, keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        bridge = NativeBridge(this)
        configureWebView()
        web.addJavascriptInterface(bridge, "KMNative")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__kmBack&&window.__kmBack())===true") { r ->
                    if (r != "true") moveTaskToBack(true) // keep the music playing
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureAudioPermission()
        }

        web.loadUrl(START_URL)
        pendingPlay?.let { id ->
            pendingPlay = null
            web.postDelayed({ runJs("window.__kmNative&&window.__kmNative('playId'," + org.json.JSONObject.quote(id) + ")") }, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.safeBrowsingEnabled = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                if (u.host == HOST && u.path == "/__proxy") return proxy(u)
                if (u.host == HOST && (u.path ?: "").startsWith("/music/")) return serveMusic(request)
                if (u.host == HOST && (u.path ?: "").startsWith("/cover/")) return serveCover(u)
                return loader.shouldInterceptRequest(u)
            }

            // Never navigate away from the bundled app
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != HOST
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf(
                            "audio/*", "video/mp4", "video/webm",
                            "application/ogg", "application/octet-stream"
                        )
                    )
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }

    /** Lets "add from link" fetch any direct audio URL without browser CORS limits. */
    private fun proxy(u: Uri): WebResourceResponse {
        val target = u.getQueryParameter("u")
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return errorResponse(400, "Bad Request")
        }
        return try {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KMusic")
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                errorResponse(code, "Error")
            } else {
                val mime = (c.contentType ?: "application/octet-stream").substringBefore(';').trim()
                WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), c.inputStream)
            }
        } catch (e: Exception) {
            errorResponse(502, "Bad Gateway")
        }
    }

    /** Serves a song's saved cover photo. */
    private fun serveCover(u: Uri): WebResourceResponse {
        val id = u.lastPathSegment ?: return errorResponse(404, "Not Found")
        val f = store.coverFor(id) ?: return errorResponse(404, "Not Found")
        return try {
            val mime = when {
                f.inputStream().use { i -> val b = ByteArray(4); i.read(b); b[0] == 0x89.toByte() && b[1] == 0x50.toByte() } -> "image/png"
                else -> "image/jpeg"
            }
            WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "max-age=3600"), FileInputStream(f))
        } catch (e: Exception) {
            errorResponse(404, "Not Found")
        }
    }

    /** Serves saved songs to the player with Range support, so seeking works offline. */
    private fun serveMusic(req: WebResourceRequest): WebResourceResponse {
        val id = req.url.lastPathSegment ?: return errorResponse(404, "Not Found")
        val f = store.fileFor(id) ?: return errorResponse(404, "Not Found")
        val len = f.length()
        val mime = store.mimeFor(id)
        var start = 0L
        var end = len - 1
        var partial = false
        val header = req.requestHeaders["Range"] ?: req.requestHeaders["range"]
        if (header != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim())
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0L, len - b.toLong())
                }
                partial = true
            }
        }
        if (len == 0L || start > end || start >= len) {
            return WebResourceResponse(
                "text/plain", "utf-8", 416, "Range Not Satisfiable",
                mapOf("Content-Range" to "bytes */$len"), ByteArrayInputStream(ByteArray(0))
            )
        }
        val input = FileInputStream(f)
        input.channel.position(start)
        val count = end - start + 1
        val headers = HashMap<String, String>()
        headers["Accept-Ranges"] = "bytes"
        headers["Content-Length"] = count.toString()
        headers["Cache-Control"] = "no-store"
        if (partial) headers["Content-Range"] = "bytes $start-$end/$len"
        return WebResourceResponse(
            mime, null, if (partial) 206 else 200, if (partial) "Partial Content" else "OK",
            headers, LimitedStream(input, count)
        )
    }

    private class LimitedStream(private val s: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = s.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = s.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }

        override fun available(): Int = minOf(s.available().toLong(), left).toInt()
        override fun close() = s.close()
    }

    private fun errorResponse(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    // ---- helpers used by NativeBridge / PlaybackService ----

    fun runJs(js: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }

    fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/*", "video/mp4", "video/webm", "application/ogg", "application/octet-stream")
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            importPicker.launch(intent)
        } catch (e: Exception) {
            runJs("window.__kmImported&&window.__kmImported(0,0)")
        }
    }

    private fun audioPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioAccess() =
        ContextCompat.checkSelfPermission(this, audioPerm()) == PackageManager.PERMISSION_GRANTED

    /** On launch: ask once for music access, or quietly pick up new songs if it is already allowed. */
    private fun ensureAudioPermission() {
        val prefs = getSharedPreferences("scan", MODE_PRIVATE)
        if (hasAudioAccess()) {
            scanPhone(true)
        } else if (!prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            askedByUser = false
            audioPermission.launch(audioPerm())
        }
    }

    /** The "Import music from this phone" button. */
    fun importFromPhone() {
        if (hasAudioAccess()) {
            scanPhone(false)
        } else {
            askedByUser = true
            audioPermission.launch(audioPerm())
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAudioAccess() && System.currentTimeMillis() - lastScan > 30_000) scanPhone(true)
    }

    /**
     * Copies every song found on the phone (Downloads, Music, other apps) into the library.
     * Songs already imported, or deleted by the user, are remembered and skipped.
     */
    private fun scanPhone(silent: Boolean) {
        if (scanning || !::store.isInitialized) return
        scanning = true
        lastScan = System.currentTimeMillis()
        if (!silent) Toast.makeText(this, "Importing music from this phone…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var bad = 0
            try {
                val prefs = getSharedPreferences("scan", MODE_PRIVATE)
                val seen = HashSet<String>(prefs.getStringSet("ids", emptySet()) ?: emptySet())
                val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val sel = MediaStore.Audio.Media.SIZE + " > 20000 AND " +
                    "COALESCE(" + MediaStore.Audio.Media.IS_RINGTONE + ",0)=0 AND " +
                    "COALESCE(" + MediaStore.Audio.Media.IS_NOTIFICATION + ",0)=0 AND " +
                    "COALESCE(" + MediaStore.Audio.Media.IS_ALARM + ",0)=0"
                contentResolver.query(base, arrayOf(MediaStore.Audio.Media._ID), sel, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val mid = c.getLong(0).toString()
                        if (mid in seen) continue
                        val uri = ContentUris.withAppendedId(base, c.getLong(0))
                        if (store.importUri(uri) != null) ok++ else bad++
                        seen.add(mid)
                        if ((ok + bad) % 10 == 0) {
                            prefs.edit().putStringSet("ids", HashSet<String>(seen)).apply()
                            runJs("window.__kmLibraryChanged&&window.__kmLibraryChanged('')")
                        }
                    }
                }
                prefs.edit().putStringSet("ids", HashSet<String>(seen)).apply()
            } catch (e: Exception) {
                bad++
            }
            scanning = false
            if (!silent || ok > 0) runJs("window.__kmImported&&window.__kmImported($ok,$bad)")
        }.start()
    }

    fun requestMic() {
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        val bg = if (isDark) Color.BLACK else Color.WHITE
        root.setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        insetsController.isAppearanceLightStatusBars = !isDark
        insetsController.isAppearanceLightNavigationBars = !isDark
    }

    fun setCarMode(on: Boolean) {
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        bridge.release()
        if (isFinishing) PlaybackService.stop(applicationContext)
        web.stopLoading()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
