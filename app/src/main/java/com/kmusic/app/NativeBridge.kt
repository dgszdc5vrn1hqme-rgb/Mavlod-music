package com.kmusic.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/** Exposed to the web page as `window.KMNative`. */
class NativeBridge(private val act: MainActivity) {

    private val main = Handler(Looper.getMainLooper())

    // ---------- now playing / notification ----------

    @JavascriptInterface
    fun nowPlaying(title: String, artist: String, durMs: Double, posMs: Double, playing: Boolean) {
        PlaybackService.push(act.applicationContext, title, artist, durMs.toLong(), posMs.toLong(), playing)
    }

    // ---------- music library (database + files) ----------

    /** JSON array of all saved songs, newest first. */
    @JavascriptInterface
    fun songsList(): String = try { act.store.list().toString() } catch (e: Exception) { JSONArray().toString() }

    /** Opens the file picker; the page gets window.__kmImported(added, failed) when done. */
    @JavascriptInterface
    fun importFromPhone() {
        main.post { act.importFromPhone() }
    }

    @JavascriptInterface
    fun pickAudio() {
        main.post { act.pickAudio() }
    }

    @JavascriptInterface
    fun songDelete(id: String): Boolean = try { act.store.delete(id) } catch (e: Exception) { false }

    @JavascriptInterface
    fun songRename(id: String, title: String, artist: String): Boolean =
        try { act.store.rename(id, title, artist) } catch (e: Exception) { false }

    @JavascriptInterface
    fun songPlayed(id: String) {
        try { act.store.played(id) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun songLiked(id: String, liked: Boolean): Boolean =
        try { act.store.setLiked(id, liked) } catch (e: Exception) { false }

    @JavascriptInterface
    fun songsDeleteAll() {
        try { act.store.deleteAll() } catch (e: Exception) { }
    }

    /** Bytes used by saved music. */
    @JavascriptInterface
    fun storageUsed(): Double = try { act.store.usedBytes().toDouble() } catch (e: Exception) { 0.0 }

    // ---------- videos on the phone (listed in place, not copied) ----------

    @JavascriptInterface
    fun videoAccess(): Boolean = act.hasVideoAccess()

    @JavascriptInterface
    fun askVideoAccess() {
        main.post { act.requestVideoAccess() }
    }

    /** JSON array of every video on the phone, newest first: id, title, durationMs, size. */
    @JavascriptInterface
    fun phoneVideos(): String {
        val out = JSONArray()
        if (!act.hasVideoAccess()) return out.toString()
        try {
            val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cols = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE
            )
            act.contentResolver.query(base, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                while (c.moveToNext() && out.length() < 1000) {
                    out.put(
                        JSONObject()
                            .put("id", c.getLong(0).toString())
                            .put("title", (c.getString(1) ?: "Video").substringBeforeLast('.'))
                            .put("durationMs", c.getLong(2))
                            .put("size", c.getLong(3))
                    )
                }
            }
        } catch (e: Exception) { }
        return out.toString()
    }

    @JavascriptInterface
    fun openPhoneVideo(id: String): Boolean = try {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toLong())
        act.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    } catch (e: Exception) { false }

    // ---------- downloads ----------

    /** Starts a download. Returns {"ok":true,"id":...} or {"ok":false,"error":"..."}. */
    @JavascriptInterface
    fun dlAdd(url: String): String = try {
        Downloads.add(url).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "Could not start the download.").toString()
    }

    /** Same as dlAdd, but with the song name, artist and cover picture link from the search results. */
    @JavascriptInterface
    fun dlAddMeta(url: String, title: String, artist: String, cover: String): String = try {
        Downloads.add(url, title, artist, cover).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "Could not start the download.").toString()
    }

    /** Like dlAddMeta, plus kind ("audio", "video" or "") and a quality label such as "MP3 · 128 kbps". */
    @JavascriptInterface
    fun dlAddEx(url: String, title: String, artist: String, cover: String, kind: String, quality: String): String = try {
        Downloads.add(url, title, artist, cover, kind, quality).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "Could not start the download.").toString()
    }

    @JavascriptInterface
    fun dlPauseAll() { try { Downloads.pauseAll() } catch (e: Exception) { } }

    @JavascriptInterface
    fun dlResumeAll() { try { Downloads.resumeAll() } catch (e: Exception) { } }

    /** Opens a finished video in the phone's video player. */
    @JavascriptInterface
    fun dlOpen(id: String): Boolean = try { Downloads.openResult(id) } catch (e: Exception) { false }

    /** JSON array of downloads, newest first. The page refreshes it when window.__kmDownloads() fires. */
    @JavascriptInterface
    fun dlList(): String = try { Downloads.list().toString() } catch (e: Exception) { JSONArray().toString() }

    @JavascriptInterface
    fun dlPause(id: String) {
        Downloads.pause(id)
    }

    @JavascriptInterface
    fun dlResume(id: String): Boolean = try { Downloads.resume(id) } catch (e: Exception) { false }

    @JavascriptInterface
    fun dlCancel(id: String) {
        try { Downloads.cancel(id) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun dlClearFinished() {
        try { Downloads.clearFinished() } catch (e: Exception) { }
    }

    // ---------- look & feel ----------

    @JavascriptInterface
    fun setTheme(dark: Boolean) {
        main.post { act.applyTheme(dark) }
    }

    @JavascriptInterface
    fun setCarMode(on: Boolean) {
        main.post { act.setCarMode(on) }
    }

    @JavascriptInterface
    fun toast(message: String) {
        main.post { Toast.makeText(act, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------- voice (car mode) ----------

    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    @JavascriptInterface
    fun hasVoice(): Boolean = SpeechRecognizer.isRecognitionAvailable(act)

    @JavascriptInterface
    fun startVoice(lang: String) {
        main.post { beginVoice(lang) }
    }

    @JavascriptInterface
    fun stopVoice() {
        main.post { destroyRecognizer() }
    }

    fun onMicPermissionResult(granted: Boolean) {
        val lang = pendingLang
        pendingLang = null
        if (lang == null) return
        if (granted) {
            beginVoice(lang)
        } else {
            voiceEvent("error", "not-allowed")
            voiceEvent("end", "")
        }
    }

    private fun beginVoice(lang: String) {
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLang = lang
            act.requestMic()
            return
        }
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = firstResult(partialResults)
                if (t.isNotEmpty()) voiceEvent("partial", t)
            }

            override fun onResults(results: Bundle?) {
                voiceEvent("final", firstResult(results))
                voiceEvent("end", "")
                destroyRecognizer()
            }

            override fun onError(error: Int) {
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "network"
                    else -> "aborted"
                }
                voiceEvent("error", code)
                voiceEvent("end", "")
                destroyRecognizer()
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            voiceEvent("error", "aborted")
            voiceEvent("end", "")
            destroyRecognizer()
        }
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private fun voiceEvent(type: String, text: String) {
        act.runJs(
            "window.__kmVoice&&window.__kmVoice(" +
                JSONObject.quote(type) + "," + JSONObject.quote(text) + ")"
        )
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    // ---------- save a file into Downloads/K Music ----------

    private var saveOut: OutputStream? = null
    private var saveUri: Uri? = null

    @JavascriptInterface
    @Synchronized
    fun saveBegin(name: String, mime: String): Boolean {
        saveAbort()
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (mime.isEmpty()) "application/octet-stream" else mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/K Music")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val out = act.contentResolver.openOutputStream(uri)
            if (out == null) {
                act.contentResolver.delete(uri, null, null)
                return false
            }
            saveUri = uri
            saveOut = out
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun saveChunk(base64: String) {
        try {
            saveOut?.write(Base64.decode(base64, Base64.DEFAULT))
        } catch (e: Exception) {
            saveAbort()
        }
    }

    @JavascriptInterface
    @Synchronized
    fun saveEnd() {
        val uri = saveUri ?: return
        try {
            saveOut?.flush()
            saveOut?.close()
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            act.contentResolver.update(uri, v, null, null)
            toast("Saved to Downloads/K Music")
        } catch (e: Exception) {
            toast("Could not save the file")
        } finally {
            saveOut = null
            saveUri = null
        }
    }

    @JavascriptInterface
    @Synchronized
    fun saveAbort() {
        try {
            saveOut?.close()
        } catch (e: Exception) {
        }
        saveUri?.let {
            try {
                act.contentResolver.delete(it, null, null)
            } catch (e: Exception) {
            }
        }
        saveOut = null
        saveUri = null
    }

    fun release() {
        main.post { destroyRecognizer() }
        saveAbort()
    }

    /** Opens a website in the phone's browser (for the "where to get music" list). */
    @JavascriptInterface
    fun openUrl(url: String) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return
        main.post {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(act, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
