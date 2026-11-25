package app.phase.stem.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.phase.stem.MainActivity
import app.phase.stem.PhaseApp
import app.phase.stem.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps playback alive in the background (foreground service of type
 * mediaPlayback) and exposes a MediaSession for lock screen, Bluetooth and
 * headset controls. Audio itself always runs in the native engine.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private val player get() = (application as PhaseApp).graph.player

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Phase").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.setPlaying(true)
                override fun onPause() = player.setPlaying(false)
                override fun onSeekTo(pos: Long) = player.seek(pos / 1000.0)
                override fun onStop() = player.setPlaying(false)
                override fun onSkipToNext() = player.skip(10.0)
                override fun onSkipToPrevious() = player.skip(-10.0)
            })
            isActive = true
        }
        scope.launch {
            player.state.map { it.track?.id to it.playing }
                .distinctUntilChanged()
                .collect { update() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePlay()
            ACTION_STOP -> {
                player.setPlaying(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        // Android 12+ may refuse re-promotion while the app is in the background
        // (e.g. resumed from a Bluetooth button); playback continues regardless.
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, n, type) }
    }

    private fun update() {
        val s = player.state.value
        val t = s.track
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t?.title ?: "Phase")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (s.duration * 1000).toLong())
                .apply {
                    val art = t?.let { (application as PhaseApp).graph.storage.artwork(it.id) }
                    if (art?.exists() == true) {
                        BitmapFactory.decodeFile(art.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
                            ?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                    }
                }
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (s.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (s.position * 1000).toLong(), if (s.playing) player.mix.value.speed else 0f,
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        if (!s.playing) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        else startInForeground()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
        val s = player.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.track?.title ?: "Phase")
            .setContentText(s.track?.artist ?: if (s.stemMode) "Stem mix" else "Full mix")
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (s.playing) "Pause" else "Play", toggle,
            )
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 4202
        const val ACTION_TOGGLE = "app.phase.stem.TOGGLE"
        const val ACTION_STOP = "app.phase.stem.STOP"

        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
        }
    }
}
