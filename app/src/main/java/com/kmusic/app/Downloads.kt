package com.kmusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads audio from direct links into the music library.
 * Three at a time, speed, pause/resume, retry, MP3 and MP4, and a notification while it works.
 */
object Downloads {

    const val CHANNEL = "kmusic_downloads"
    const val NOTIF_ID = 4201
    private const val UA = "Mozilla/5.0 (Linux; Android 12) KMusic"

    private class Fail(msg: String) : Exception(msg)

    private class Task(val id: String, val url: String) {
        @Volatile var stop = 0          // 0 running, 1 pause, 2 cancel
        @Volatile var total = 0L
        @Volatile var done = 0L
        @Volatile var speed = 0L        // bytes per second, smoothed
    }

    private lateinit var app: Context
    private lateinit var store: MusicStore
    private var ready = false
    private val pool = Executors.newFixedThreadPool(3)
    private val tasks = ConcurrentHashMap<String, Task>()
    @Volatile private var lastPing = 0L

    private val dir get() = File(app.filesDir, "downloads")
    private fun part(id: String) = File(dir, "$id.part")

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        store = MusicStore.get(app)
        dir.mkdirs()
        val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        // anything that was running when the app died can be resumed by the user
        store.db.writableDatabase.execSQL(
            "UPDATE downloads SET status='paused' WHERE status IN ('queued','downloading')"
        )
        // part files with no row are junk
        val rows = HashSet<String>()
        store.db.readableDatabase.query("downloads", arrayOf("id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) rows.add(c.getString(0))
        }
        dir.listFiles()?.forEach { if (it.name.removeSuffix(".part") !in rows) it.delete() }
        ready = true
    }

    // ---------- commands from the page ----------

    fun add(raw: String, title: String = "", artist: String = "", cover: String = "", kind: String = "", quality: String = ""): JSONObject {
        val url = raw.trim()
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true)) || host.isNullOrEmpty()) {
            return error("Paste a full link that starts with http or https.")
        }
        platform(host)?.let {
            return error("$it links cannot be saved here. Paste a direct link to an audio file, add files from your phone, or use Find free music.")
        }
        val dup = store.db.readableDatabase.query(
            "downloads", arrayOf("id"), "url=? AND status IN ('queued','downloading','paused')",
            arrayOf(url), null, null, null
        ).use { it.moveToFirst() }
        if (dup) return error("This link is already in your downloads.")

        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val v = ContentValues().apply {
            put("id", id)
            put("url", url)
            put("title", title.trim().ifEmpty { guessTitle(url) })
            put("status", "queued")
            put("hint_title", title.trim().take(200))
            put("hint_artist", artist.trim().take(200))
            put("cover_url", if (cover.startsWith("https://") || cover.startsWith("http://")) cover.trim() else "")
            put("kind", if (kind == "audio" || kind == "video") kind else "")
            put("quality", quality.trim().take(60))
            put("created_at", System.currentTimeMillis())
        }
        store.db.writableDatabase.insert("downloads", null, v)
        launch(id, url)
        return JSONObject().put("ok", true).put("id", id)
    }

    /** Continue a paused or failed download. */
    fun resume(id: String): Boolean {
        if (tasks.containsKey(id)) return false
        val url = rowValue(id, "url") ?: return false
        val st = rowValue(id, "status")
        if (st != "paused" && st != "error") return false
        launch(id, url)
        return true
    }

    fun pause(id: String) {
        tasks[id]?.stop = 1
    }

    fun pauseAll() {
        tasks.values.forEach { it.stop = 1 }
    }

    fun resumeAll() {
        val ids = ArrayList<String>()
        store.db.readableDatabase.query("downloads", arrayOf("id"), "status IN ('paused','error')", null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        ids.forEach { resume(it) }
    }

    /** Opens a finished video in the phone's video player. */
    fun openResult(id: String): Boolean {
        val uri = rowValue(id, "result").orEmpty()
        if (rowValue(id, "kind") != "video" || uri.isEmpty()) return false
        return try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(i)
            true
        } catch (e: Exception) { false }
    }

    fun cancel(id: String) {
        val t = tasks[id]
        if (t != null) {
            t.stop = 2
        } else {
            part(id).delete()
            store.db.writableDatabase.delete("downloads", "id=?", arrayOf(id))
            ping(true)
        }
    }

    fun clearFinished() {
        val w = store.db.writableDatabase
        val ids = ArrayList<String>()
        w.query("downloads", arrayOf("id"), "status IN ('done','error')", null, null, null, null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        ids.forEach {
            if (!tasks.containsKey(it)) {
                part(it).delete()
                w.delete("downloads", "id=?", arrayOf(it))
            }
        }
        ping(true)
    }

    fun list(): JSONArray {
        val out = JSONArray()
        store.db.readableDatabase.query("downloads", null, null, null, null, null, "created_at DESC").use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val t = tasks[id]
                val done = t?.done ?: part(id).let { if (it.exists()) it.length() else 0L }
                val total = t?.total ?: 0L
                val status = c.getString(c.getColumnIndexOrThrow("status"))
                val pct = when {
                    status == "done" -> 100
                    total > 0 -> ((done * 100) / total).toInt().coerceIn(0, 99)
                    else -> 0
                }
                out.put(
                    JSONObject()
                        .put("id", id)
                        .put("url", c.getString(c.getColumnIndexOrThrow("url")))
                        .put("title", c.getString(c.getColumnIndexOrThrow("title")))
                        .put("status", status)
                        .put("progress", pct)
                        .put("doneBytes", done)
                        .put("totalBytes", total)
                        .put("speed", if (status == "downloading") (t?.speed ?: 0L) else 0L)
                        .put("kind", c.getString(c.getColumnIndexOrThrow("kind")))
                        .put("quality", c.getString(c.getColumnIndexOrThrow("quality")))
                        .put("result", c.getString(c.getColumnIndexOrThrow("result")))
                        .put("cover", c.getString(c.getColumnIndexOrThrow("cover_url")))
                        .put("artist", c.getString(c.getColumnIndexOrThrow("hint_artist")))
                        .put("error", c.getString(c.getColumnIndexOrThrow("error")))
                )
            }
        }
        return out
    }

    // ---------- the work ----------

    private fun launch(id: String, url: String) {
        val t = Task(id, url)
        tasks[id] = t
        setStatus(id, "queued")
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        pool.execute { work(t) }
        ping(true)
    }

    private fun work(t: Task) {
        val id = t.id
        val file = part(id)
        try {
            if (t.stop != 0) {
                if (t.stop == 1) setStatus(id, "paused")
                return
            }
            setStatus(id, "downloading")
            ping(true)

            var existing = if (file.exists()) file.length() else 0L
            var url = t.url
            var hops = 0
            var conn = open(url, existing)
            while (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null || ++hops > 5) throw Fail("The link redirects too many times.")
                url = URL(URL(url), loc).toString()
                conn = open(url, existing)
            }
            val code = conn.responseCode
            if (code == 416) {              // saved part does not fit the file: start again
                conn.disconnect()
                file.delete()
                work(t)
                return
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw Fail(httpMessage(code))
            }
            val type = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            if (type.startsWith("text/") || type.contains("html") || type == "application/json") {
                conn.disconnect()
                throw Fail("This link is a web page, not an audio file.")
            }
            val isVideo = type.startsWith("video/") ||
                fileName(conn, url).substringAfterLast('.', "").lowercase() in setOf("mp4", "webm", "m4v", "mov", "mkv") ||
                rowValue(id, "kind") == "video"
            if (rowValue(id, "kind").isNullOrEmpty()) setKind(id, if (isVideo) "video" else "audio")
            val resumed = code == 206
            if (!resumed && existing > 0) {
                file.delete()
                existing = 0
            }
            val len = conn.contentLengthLong
            t.done = existing
            t.total = if (len > 0) existing + len else 0L
            val name = fileName(conn, url)
            if (rowValue(id, "hint_title").isNullOrEmpty()) setTitle(id, prettyTitle(name))

            conn.inputStream.use { input ->
                FileOutputStream(file, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    var sampleT = SystemClock.elapsedRealtime()
                    var sampleB = t.done
                    while (t.stop == 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        t.done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - sampleT >= 700) {
                            val inst = (t.done - sampleB) * 1000 / (now - sampleT)
                            t.speed = if (t.speed == 0L) inst else (t.speed * 6 + inst * 4) / 10
                            sampleT = now
                            sampleB = t.done
                        }
                        if (now - last > 400) {
                            last = now
                            ping(false)
                        }
                    }
                }
            }
            conn.disconnect()

            when (t.stop) {
                1 -> { setStatus(id, "paused"); return }
                2 -> { file.delete(); store.db.writableDatabase.delete("downloads", "id=?", arrayOf(id)); return }
            }
            if (t.total > 0 && t.done < t.total) {
                throw Fail("The connection was lost. Tap Retry to continue.")
            }

            if (rowValue(id, "kind") == "video") {
                val ext = extFor(name, type).let { if (it in setOf("mp4", "webm")) it else "mp4" }
                val title = rowValue(id, "title").orEmpty().ifEmpty { prettyTitle(name) }
                val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "Video" }
                val uri = exportVideo(file, "$safe.$ext", if (ext == "webm") "video/webm" else "video/mp4")
                setResult(id, uri)
                setStatus(id, "done")
                return
            }
            val (sid, dest) = store.newFile(extFor(name, type))
            if (!file.renameTo(dest)) {
                file.copyTo(dest, true)
                file.delete()
            }
            val song = store.addDownloaded(sid, dest, name, sourceLabel(url), url, type)
                ?: throw Fail("This file is not playable audio.")
            val songId = song.optString("id")
            val ht = rowValue(id, "hint_title").orEmpty()
            val ha = rowValue(id, "hint_artist").orEmpty()
            if (ht.isNotEmpty()) {
                store.rename(songId, ht, ha.ifEmpty { song.optString("artist", "Unknown artist") })
            }
            if (!store.hasCover(songId)) fetchCover(songId, rowValue(id, "cover_url").orEmpty())
            setResult(id, songId)
            setStatus(id, "done")
            library(songId)
        } catch (e: Fail) {
            setStatus(t.id, "error", e.message ?: "Download failed.")
        } catch (e: UnknownHostException) {
            setStatus(t.id, "error", "No internet connection.")
        } catch (e: SocketTimeoutException) {
            setStatus(t.id, "error", "The connection timed out. Tap Retry to continue.")
        } catch (e: Exception) {
            setStatus(t.id, "error", "Download failed. Tap Retry to try again.")
        } finally {
            val stopped = t.stop
            tasks.remove(id)
            if (stopped == 2) {
                part(id).delete()
                store.db.writableDatabase.delete("downloads", "id=?", arrayOf(id))
            }
            if (tasks.isEmpty()) app.stopService(Intent(app, DownloadService::class.java))
            ping(true)
        }
    }

    /** Saves the cover photo next to the song. Never fails the download if it cannot. */
    private fun fetchCover(songId: String, url: String) {
        if (url.isEmpty()) return
        try {
            var u = url
            var hops = 0
            var c = open(u, 0)
            while (c.responseCode in 300..399 && hops++ < 5) {
                val loc = c.getHeaderField("Location") ?: break
                c.disconnect()
                u = URL(URL(u), loc).toString()
                c = open(u, 0)
            }
            if (c.responseCode in 200..299) {
                val bytes = c.inputStream.use { it.readBytes() }
                store.saveCover(songId, bytes)
            }
            c.disconnect()
        } catch (e: Exception) { }
    }

    private fun open(u: String, from: Long): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    // ---------- notification + page events ----------

    fun notification(): android.app.Notification {
        val active = tasks.values.toList()
        val total = active.sumOf { it.total }
        val done = active.sumOf { it.done }
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (active.size > 1) "Downloading ${active.size} files" else "Downloading")
            .setContentText(
                (if (pct >= 0) "$pct%" else "Starting…") +
                    active.sumOf { it.speed }.let { if (it > 0) " · " + speedText(it) else "" }
            )
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun ping(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPing < 400) return
        lastPing = now
        MainActivity.current?.runJs("window.__kmDownloads&&window.__kmDownloads()")
        if (tasks.isNotEmpty()) {
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private fun library(songId: String) {
        MainActivity.current?.runJs(
            "window.__kmLibraryChanged&&window.__kmLibraryChanged(" + JSONObject.quote(songId) + ")"
        )
    }

    // ---------- database helpers ----------

    private fun setStatus(id: String, status: String, err: String = "") {
        val v = ContentValues().apply {
            put("status", status)
            put("error", err)
        }
        store.db.writableDatabase.update("downloads", v, "id=?", arrayOf(id))
    }

    private fun setKind(id: String, kind: String) {
        val v = ContentValues().apply { put("kind", kind) }
        store.db.writableDatabase.update("downloads", v, "id=?", arrayOf(id))
    }

    private fun setResult(id: String, result: String) {
        val v = ContentValues().apply { put("result", result) }
        store.db.writableDatabase.update("downloads", v, "id=?", arrayOf(id))
    }

    /** Copies a finished video into Movies/K Music so the gallery and video players can see it. */
    private fun exportVideo(src: File, name: String, mime: String): String {
        val r = app.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/K Music")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw Fail("Could not save the video.")
        try {
            val out = r.openOutputStream(uri) ?: throw Fail("Could not save the video.")
            out.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            r.update(uri, done, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw if (e is Fail) e else Fail("Not enough space to save the video.")
        }
        src.delete()
        return uri.toString()
    }

    private fun setTitle(id: String, title: String) {
        val v = ContentValues().apply { put("title", title) }
        store.db.writableDatabase.update("downloads", v, "id=?", arrayOf(id))
    }

    private fun rowValue(id: String, col: String): String? {
        store.db.readableDatabase.query("downloads", arrayOf(col), "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun speedText(b: Long): String =
        if (b >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB/s", b / 1e6) else "${maxOf(1L, b / 1000)} KB/s"

    private fun error(msg: String) = JSONObject().put("ok", false).put("error", msg)

    // ---------- naming and checks ----------

    private fun platform(host: String): String? {
        val h = host.lowercase()
        return when {
            h.endsWith("youtube.com") || h == "youtu.be" -> "YouTube"
            h.endsWith("tiktok.com") -> "TikTok"
            h.endsWith("instagram.com") -> "Instagram"
            h.endsWith("facebook.com") || h == "fb.watch" -> "Facebook"
            h == "x.com" || h.endsWith(".x.com") || h.endsWith("twitter.com") -> "X"
            h.endsWith("soundcloud.com") -> "SoundCloud"
            h.endsWith("spotify.com") -> "Spotify"
            h.endsWith("vimeo.com") -> "Vimeo"
            else -> null
        }
    }

    private fun sourceLabel(url: String): String {
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return "link"
        return host.removePrefix("www.")
    }

    private fun guessTitle(url: String): String {
        val last = try { Uri.parse(url).lastPathSegment } catch (e: Exception) { null }
        return if (!last.isNullOrBlank()) prettyTitle(Uri.decode(last)) else sourceLabel(url)
    }

    private fun prettyTitle(name: String) = name.substringBeforeLast('.', name).replace('_', ' ').trim().ifEmpty { "Track" }

    private fun fileName(c: HttpURLConnection, url: String): String {
        val cd = c.getHeaderField("Content-Disposition")
        if (cd != null) {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                val n = Uri.decode(it.groupValues[1]).trim()
                if (n.isNotEmpty()) return n
            }
        }
        val last = try { Uri.parse(url).lastPathSegment } catch (e: Exception) { null }
        return if (!last.isNullOrBlank()) Uri.decode(last) else "Track"
    }

    private fun extFor(name: String, mime: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        if (e in setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "mp4", "webm")) return e
        return when (mime) {
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> "mp3"
        }
    }

    private fun httpMessage(code: Int) = when (code) {
        401, 403 -> "The site refused the download (error $code)."
        404 -> "The file was not found (error 404)."
        in 500..599 -> "The site has a problem right now (error $code)."
        else -> "The download failed (error $code)."
    }
}
