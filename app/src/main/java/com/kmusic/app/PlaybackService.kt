package com.kmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import org.json.JSONObject

/**
 * Keeps the app alive while music plays in the background and shows the
 * notification / lock-screen controls. The audio itself plays in the web page;
 * this service only mirrors its state and sends button presses back to it.
 * It is also the Android Auto entry point: the car screen browses the library
 * here and asks it to play a song.
 */
class PlaybackService : MediaBrowserServiceCompat() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        private const val A_UPDATE = "com.kmusic.app.UPDATE"
        private const val A_PLAY = "com.kmusic.app.PLAY"
        private const val A_PAUSE = "com.kmusic.app.PAUSE"
        private const val A_NEXT = "com.kmusic.app.NEXT"
        private const val A_PREV = "com.kmusic.app.PREV"

        @Volatile
        var running = false

        fun push(ctx: Context, title: String, artist: String, dur: Long, pos: Long, playing: Boolean) {
            if (!playing && !running) return
            val i = Intent(ctx, PlaybackService::class.java).setAction(A_UPDATE)
                .putExtra("t", title).putExtra("a", artist)
                .putExtra("d", dur).putExtra("p", pos).putExtra("pl", playing)
            try {
                if (playing) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
            }
        }

        private fun js(cmd: String, arg: String = "null"): String =
            "window.__kmNative&&window.__kmNative('$cmd',$arg)"

        fun dispatch(cmd: String, arg: String = "null") {
            MainActivity.current?.runJs(js(cmd, arg))
        }
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var nm: NotificationManager
    private lateinit var am: AudioManager
    private var wake: PowerManager.WakeLock? = null
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var noisyRegistered = false
    private var isFg = false

    private var title = "K Music"
    private var artist = ""
    private var dur = 0L
    private var pos = 0L
    private var playing = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) dispatch("pause")
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocus = false
                dispatch("pause")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) {
                    resumeOnFocus = true
                    dispatch("pause")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    dispatch("play")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NotificationManager::class.java)
        am = getSystemService(AudioManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        session = MediaSessionCompat(this, "KMusic")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { if (mediaId != null) playSong(mediaId) }
            override fun onPlayFromSearch(query: String?, extras: Bundle?) = playSearch(query)
            override fun onPlay() = dispatch("play")
            override fun onPause() = dispatch("pause")
            override fun onSkipToNext() = dispatch("next")
            override fun onSkipToPrevious() = dispatch("prev")
            override fun onSeekTo(position: Long) = dispatch("seek", (position / 1000.0).toString())
        })
        session.setSessionActivity(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        session.isActive = true
        sessionToken = session.sessionToken
    }

    // ---------- Android Auto: browse the library and play from the car screen ----------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        Thread {
            val out = ArrayList<MediaBrowserCompat.MediaItem>()
            try {
                val rows = MusicStore.get(applicationContext).list()
                val items = ArrayList<JSONObject>()
                for (i in 0 until rows.length()) items.add(rows.getJSONObject(i))
                if (parentId == "root") {
                    out.add(folder("recent", "Recently added"))
                    out.add(folder("liked", "Liked songs"))
                    out.add(folder("artists", "Artists"))
                    out.add(folder("all", "All songs (${items.size})"))
                } else if (parentId == "artists") {
                    items.groupBy { it.optString("artist").ifBlank { "Unknown artist" } }
                        .toSortedMap(compareBy { it.lowercase() })
                        .entries.take(1000).forEach { (name, songs) ->
                            out.add(folder("artist:$name", "$name (${songs.size})"))
                        }
                } else {
                    val picked = when {
                        parentId == "liked" -> items.filter { it.optBoolean("liked") }
                        parentId == "all" -> items.sortedBy { it.optString("title").lowercase() }
                        parentId.startsWith("artist:") -> {
                            val a = parentId.removePrefix("artist:")
                            items.filter { it.optString("artist").ifBlank { "Unknown artist" } == a }
                        }
                        else -> items
                    }
                    picked.take(2000).forEach {
                        out.add(
                            MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                    .setMediaId(it.optString("id"))
                                    .setTitle(it.optString("title"))
                                    .setSubtitle(it.optString("artist"))
                                    .build(),
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                            )
                        )
                    }
                }
            } catch (e: Exception) { }
            result.sendResult(out)
        }.start()
    }

    private fun folder(id: String, name: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(name).build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )

    /** Plays a library song. If the app screen is closed, opens the app first. */
    private fun playSong(id: String) {
        if (MainActivity.current != null) {
            dispatch("playId", JSONObject.quote(id))
        } else {
            MainActivity.pendingPlay = id
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }
    }

    private fun playSearch(query: String?) {
        val rows = MusicStore.get(applicationContext).list()
        val q = (query ?: "").trim().lowercase()
        var found: String? = null
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            if (q.isEmpty() || r.optString("title").lowercase().contains(q) || r.optString("artist").lowercase().contains(q)) {
                found = r.optString("id"); break
            }
        }
        if (found != null) playSong(found)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_UPDATE -> {
                title = intent.getStringExtra("t") ?: title
                artist = intent.getStringExtra("a") ?: artist
                dur = intent.getLongExtra("d", dur)
                pos = intent.getLongExtra("p", pos)
                playing = intent.getBooleanExtra("pl", playing)
            }
            A_PLAY -> { playing = true; dispatch("play") }
            A_PAUSE -> { playing = false; dispatch("pause") }
            A_NEXT -> dispatch("next")
            A_PREV -> dispatch("prev")
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    pos, if (playing) 1f else 0f
                )
                .build()
        )

        val n = buildNotification()
        if (playing) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                isFg = true
            } catch (e: Exception) {
                nm.notify(NOTIF_ID, n)
            }
            acquireWake()
            requestFocus()
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(
                    this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                noisyRegistered = true
            }
        } else {
            if (isFg) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                isFg = false
            }
            nm.notify(NOTIF_ID, n)
            releaseWake()
            if (!resumeOnFocus) abandonFocus()
            if (noisyRegistered) {
                try { unregisterReceiver(noisy) } catch (e: Exception) { }
                noisyRegistered = false
            }
        }
    }

    private fun action(icon: Int, label: String, act: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(icon, label, pi).build()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(playing)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Previous", A_PREV))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", A_PAUSE)
                else action(R.drawable.ic_play, "Play", A_PLAY)
            )
            .addAction(action(R.drawable.ic_next, "Next", A_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KMusic:playback").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        wake = null
    }

    private fun requestFocus() {
        if (focusReq != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusReq = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        focusReq = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        releaseWake()
        abandonFocus()
        if (noisyRegistered) {
            try { unregisterReceiver(noisy) } catch (e: Exception) { }
            noisyRegistered = false
        }
        try { nm.cancel(NOTIF_ID) } catch (e: Exception) { }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
