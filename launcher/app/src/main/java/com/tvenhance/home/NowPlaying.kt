package com.tvenhance.home

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Lets ÇivitTV read media sessions (Android requires a notification listener for that).
 * The system keeps it bound, so the play history keeps recording while other apps are open.
 * Access is granted once over adb by install-launcher.ps1.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() = MediaWatcher.start(applicationContext)
}

/** Something that actually played, kept after its app closes the media session. */
data class Played(
    val pkg: String,
    val title: String,
    val subtitle: String,
    val art: String?,     // local file path or https URL
    val mediaId: String?,
    val time: Long,
)

/** What the "now playing" card shows: the playing item, or else the last one that played. */
data class NowPlaying(val item: Played, val playing: Boolean, val open: PendingIntent?)

/**
 * Watches every media session and records what plays. Apps such as YouTube drop their
 * session as soon as you leave them, so without this a paused song in another app would
 * look like the most recent thing played.
 */
object MediaWatcher {
    private const val MAX_TOTAL = 40
    private const val MAX_PER_APP = 10

    val now = MutableStateFlow<NowPlaying?>(null)
    val history = MutableStateFlow<List<Played>>(emptyList())

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var started = false
    private var loaded = false
    private lateinit var appContext: Context
    private var controllers: List<MediaController> = emptyList()

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = update()
    }

    fun start(context: Context) {
        main.post { startOnMain(context.applicationContext) }
    }

    private fun startOnMain(context: Context) {
        if (started) return
        appContext = context
        if (!loaded) {
            history.value = load()
            loaded = true
        }
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, NowPlayingListener::class.java)
        try {
            sessions.addOnActiveSessionsChangedListener({ rebind(it.orEmpty()) }, component, main)
            rebind(sessions.getActiveSessions(component))
            started = true
        } catch (e: SecurityException) {
            // Notification access not granted yet; the card shows the saved history only.
            refreshNow()
        }
    }

    private fun rebind(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        controllers.forEach { it.registerCallback(callback, main) }
        update()
    }

    private fun update() {
        for (c in controllers) {
            val m = c.metadata ?: continue
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) record(c.packageName, m)
        }
        refreshNow()
    }

    private fun refreshNow() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
        val current = playing?.let { c -> history.value.firstOrNull { it.pkg == c.packageName } }
        now.value = when {
            playing != null && current != null -> NowPlaying(current, true, playing.sessionActivity)
            history.value.isNotEmpty() -> NowPlaying(history.value.first(), false, null)
            else -> null
        }
    }

    private fun record(pkg: String, m: MediaMetadata) {
        val title = (m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() } ?: return
        val subtitle = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val time = System.currentTimeMillis()
        val list = history.value
        val prev = list.firstOrNull { it.pkg == pkg && it.title == title }
        // Same item still playing: callbacks fire constantly, so only refresh its time once a minute.
        if (prev != null && list.first() == prev && time - prev.time < 60_000) return

        val mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = listOf(
            MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.startsWith("http") } }
        val art = when {
            bitmap != null -> saveArt(bitmap, pkg, title)
            artUri != null -> artUri
            youtubeId(pkg, mediaId) != null -> "https://i.ytimg.com/vi/${youtubeId(pkg, mediaId)}/hqdefault.jpg"
            else -> prev?.art
        }

        val item = Played(pkg, title, subtitle, art, mediaId, time)
        val updated = capped(listOf(item) + list.filterNot { it.pkg == pkg && it.title == title })
        history.value = updated
        save(updated)
    }

    /**
     * Records something opened from ÇivitTV that its app won't report itself, such as a TV+
     * channel: it then shows up as "last watched" on that app's shelf.
     */
    fun opened(item: Played) {
        main.post {
            history.value = capped(listOf(item) + history.value.filterNot { it.pkg == item.pkg && it.title == item.title })
            if (::appContext.isInitialized) save(history.value)
            refreshNow()
        }
    }

    /** Newest first, at most MAX_PER_APP per app so one app (e.g. TV+ zapping) can't crowd out the rest. */
    private fun capped(list: List<Played>): List<Played> {
        val perApp = HashMap<String, Int>()
        return list.filter { (perApp.merge(it.pkg, 1, Int::plus) ?: 0) <= MAX_PER_APP }.take(MAX_TOTAL)
    }

    private fun saveArt(bitmap: Bitmap, pkg: String, title: String): String {
        val file = File(File(appContext.filesDir, "media_art").apply { mkdirs() }, "${(pkg + title).hashCode()}.png")
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val scale = 320f / maxOf(soft.width, soft.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
        io.execute { runCatching { file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        return file.absolutePath
    }

    private fun historyFile() = File(appContext.filesDir, "play_history.json")

    private fun save(list: List<Played>) {
        val json = JSONArray()
        for (p in list) {
            json.put(
                JSONObject().put("pkg", p.pkg).put("title", p.title).put("subtitle", p.subtitle)
                    .put("art", p.art ?: "").put("mediaId", p.mediaId ?: "").put("time", p.time)
            )
        }
        val keep = list.mapNotNullTo(HashSet()) { it.art }
        io.execute {
            runCatching { historyFile().writeText(json.toString()) }
            // Drop cover art of items that have fallen out of the history.
            File(appContext.filesDir, "media_art").listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
        }
    }

    private fun load(): List<Played> = runCatching {
        val json = JSONArray(historyFile().readText())
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Played(
                o.getString("pkg"), o.getString("title"), o.getString("subtitle"),
                o.getString("art").ifEmpty { null }, o.getString("mediaId").ifEmpty { null }, o.getLong("time"),
            )
        }
    }.getOrDefault(emptyList())
}

/** YouTube reports the video id as its media id; that gives us a thumbnail and a deep link. */
fun youtubeId(pkg: String, mediaId: String?): String? =
    mediaId?.takeIf { pkg.startsWith("com.google.android.youtube") && Regex("[A-Za-z0-9_-]{11}").matches(it) }

@Composable
fun rememberNowPlaying(): State<NowPlaying?> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MediaWatcher.start(context) }
    return MediaWatcher.now.collectAsState()
}

@Composable
fun rememberPlayHistory(): State<List<Played>> = MediaWatcher.history.collectAsState()
