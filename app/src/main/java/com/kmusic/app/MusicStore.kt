package com.kmusic.app

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Every downloaded or imported song lives here: the audio file in app storage
 * (filesDir/music) and its details in the database. Works fully offline.
 */
class MusicStore private constructor(private val ctx: Context) {

    companion object {
        @Volatile
        private var instance: MusicStore? = null

        fun get(ctx: Context): MusicStore =
            instance ?: synchronized(this) {
                instance ?: MusicStore(ctx.applicationContext).also { instance = it }
            }

        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "mp4", "webm")
    }

    internal val db = Db(ctx)
    val dir: File = File(ctx.filesDir, "music").apply { mkdirs() }
    private val coverDir: File = File(ctx.filesDir, "covers").apply { mkdirs() }

    // ---------- cover photos ----------

    /** The saved cover picture of a song, or null. */
    fun coverFor(id: String): File? = File(coverDir, "$id.img").takeIf { it.isFile && it.length() > 0 }

    /** Saves picture bytes as the cover, only if they really are an image. */
    @Synchronized
    fun saveCover(id: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 4_000_000) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return false
        return try { File(coverDir, "$id.img").writeBytes(bytes); true } catch (e: Exception) { false }
    }

    fun hasCover(id: String) = coverFor(id) != null

    // ---------- reading ----------

    /** All songs, newest first. Songs whose file went missing are removed first. */
    @Synchronized
    fun list(): JSONArray {
        repair()
        val out = JSONArray()
        db.readableDatabase.query("songs", null, null, null, null, null, "added_at DESC").use { c ->
            while (c.moveToNext()) out.put(row(c))
        }
        return out
    }

    @Synchronized
    fun fileFor(id: String): File? {
        db.readableDatabase.query("songs", arrayOf("file"), "id=?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            val f = File(dir, c.getString(0))
            return if (f.isFile) f else null
        }
    }

    @Synchronized
    fun mimeFor(id: String): String {
        db.readableDatabase.query("songs", arrayOf("mime"), "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else "audio/mpeg"
        }
    }

    /** Total bytes used by saved music. */
    @Synchronized
    fun usedBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    // ---------- adding ----------

    /** Copy a file the user picked (content:// uri) into the library. Returns the song, or null if it is not audio. */
    fun importUri(uri: Uri): JSONObject? {
        val name = displayName(uri)
        val ext = extOf(name, ctx.contentResolver.getType(uri))
        val id = newId()
        val f = File(dir, "$id.$ext")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { input.copyTo(it) }
            } ?: run { f.delete(); return null }
            register(id, f, name, "device", "", ctx.contentResolver.getType(uri))
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    /**
     * Add a file that is already inside the music folder (for example a finished download).
     * Use [newFile] to get a valid destination first.
     */
    fun addDownloaded(id: String, f: File, name: String, source: String, sourceUrl: String, mime: String?): JSONObject? =
        register(id, f, name, source, sourceUrl, mime)

    /** A new id and an empty destination file inside the music folder. */
    fun newFile(ext: String): Pair<String, File> {
        val id = newId()
        val clean = ext.lowercase().takeIf { it in AUDIO_EXT } ?: "mp3"
        return id to File(dir, "$id.$clean")
    }

    @Synchronized
    private fun register(id: String, f: File, name: String, source: String, sourceUrl: String, mime: String?): JSONObject? {
        val meta = readMeta(f)
        if (meta == null) {          // not playable audio
            f.delete()
            return null
        }
        val guess = parseName(name)
        val title = meta.title?.takeIf { it.isNotBlank() } ?: guess.first
        val artist = meta.artist?.takeIf { it.isNotBlank() } ?: guess.second
        val type = mimeFor(f.extension, mime)
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            r.embeddedPicture?.let { saveCover(id, it) }
            r.release()
        } catch (e: Exception) { }
        val v = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("duration_ms", meta.durationMs)
            put("file", f.name)
            put("size", f.length())
            put("mime", type)
            put("source", source)
            put("source_url", sourceUrl)
            put("added_at", System.currentTimeMillis())
        }
        if (db.writableDatabase.insert("songs", null, v) < 0) {
            f.delete()
            return null
        }
        return get(id)
    }

    // ---------- changing ----------

    @Synchronized
    fun delete(id: String): Boolean {
        val f = File(dir, rowFile(id) ?: return false)
        f.delete()
        File(coverDir, "$id.img").delete()
        return db.writableDatabase.delete("songs", "id=?", arrayOf(id)) > 0
    }

    @Synchronized
    fun rename(id: String, title: String, artist: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        val v = ContentValues().apply {
            put("title", t)
            put("artist", artist.trim().ifEmpty { "Unknown artist" })
        }
        return db.writableDatabase.update("songs", v, "id=?", arrayOf(id)) > 0
    }

    @Synchronized
    fun played(id: String) {
        db.writableDatabase.execSQL(
            "UPDATE songs SET play_count=play_count+1, last_played=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    @Synchronized
    fun setLiked(id: String, liked: Boolean): Boolean {
        val v = ContentValues().apply { put("liked", if (liked) 1 else 0) }
        return db.writableDatabase.update("songs", v, "id=?", arrayOf(id)) > 0
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        coverDir.listFiles()?.forEach { it.delete() }
        db.writableDatabase.delete("songs", null, null)
    }

    // ---------- keeping files and database in step ----------

    /** Drop rows whose file is gone, and delete files that no row points to. */
    @Synchronized
    fun repair() {
        val w = db.writableDatabase
        val known = HashSet<String>()
        val dead = ArrayList<String>()
        w.query("songs", arrayOf("id", "file"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val file = c.getString(1)
                if (File(dir, file).isFile) known.add(file) else dead.add(c.getString(0))
            }
        }
        dead.forEach { w.delete("songs", "id=?", arrayOf(it)) }
        dir.listFiles()?.forEach { if (it.isFile && it.name !in known) it.delete() }
        val live = HashSet<String>()
        w.query("songs", arrayOf("id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) live.add(c.getString(0) + ".img")
        }
        coverDir.listFiles()?.forEach { if (it.name !in live) it.delete() }
    }

    // ---------- helpers ----------

    private fun get(id: String): JSONObject? {
        db.readableDatabase.query("songs", null, "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) row(c) else null
        }
    }

    private fun rowFile(id: String): String? {
        db.readableDatabase.query("songs", arrayOf("file"), "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun row(c: android.database.Cursor): JSONObject {
        val id = c.getString(c.getColumnIndexOrThrow("id"))
        return JSONObject().apply {
            put("id", id)
            put("title", c.getString(c.getColumnIndexOrThrow("title")))
            put("artist", c.getString(c.getColumnIndexOrThrow("artist")))
            put("durationMs", c.getLong(c.getColumnIndexOrThrow("duration_ms")))
            put("size", c.getLong(c.getColumnIndexOrThrow("size")))
            put("mime", c.getString(c.getColumnIndexOrThrow("mime")))
            put("source", c.getString(c.getColumnIndexOrThrow("source")))
            put("sourceUrl", c.getString(c.getColumnIndexOrThrow("source_url")))
            put("addedAt", c.getLong(c.getColumnIndexOrThrow("added_at")))
            put("playCount", c.getInt(c.getColumnIndexOrThrow("play_count")))
            put("lastPlayed", c.getLong(c.getColumnIndexOrThrow("last_played")))
            put("liked", c.getInt(c.getColumnIndexOrThrow("liked")) == 1)
            put("src", "/music/$id")
            put("cover", if (hasCover(id)) "/cover/$id" else "")
        }
    }

    private class Meta(val title: String?, val artist: String?, val durationMs: Long)

    /** Reads tags and checks the file really holds audio. */
    private fun readMeta(f: File): Meta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (hasAudio != "yes" || dur <= 0) null
            else Meta(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                dur
            )
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: "Track"
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment ?: "Track"
    }

    private fun extOf(name: String, mime: String?): String {
        val e = name.substringAfterLast('.', "").lowercase()
        if (e in AUDIO_EXT) return e
        return when (mime?.lowercase()) {
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

    private fun mimeFor(ext: String, given: String?): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> given ?: "audio/mpeg"
    }

    /** "Artist - Title.mp3" becomes (title, artist). */
    private fun parseName(name: String): Pair<String, String> {
        val base = name.substringBeforeLast('.', name).replace('_', ' ').trim()
        val i = base.indexOf(" - ")
        return if (i > 0 && i < base.length - 3)
            base.substring(i + 3).trim() to base.substring(0, i).trim()
        else
            base.ifEmpty { "Track" } to "Unknown artist"
    }
}
