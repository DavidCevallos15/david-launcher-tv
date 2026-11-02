package dev.davidcevallos.launchertv

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Lo que suena ahora mismo en cualquier app. */
class Track(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
)

/**
 * Sesiones de medios activas del sistema: qué suena (en cualquier app, incluso en segundo plano) y los
 * controles anterior / pausa / siguiente / cerrar. Todo en el hilo principal.
 *
 * Android solo entrega las sesiones a apps con "acceso a notificaciones" (ver [MediaListenerService]);
 * sin él [hasAccess] es false y no hay reproductor, el resto del launcher funciona igual.
 */
object MediaHub {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Track?) -> Unit>()
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null

    var track: Track? = null
        private set

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    /** Empieza a seguir las sesiones. Idempotente; si falta el acceso no hace nada. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        if (!hasAccess(ctx)) return
        val sm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(ctx, MediaListenerService::class.java)
        try {
            sm.removeOnActiveSessionsChangedListener(sessionsChanged)
            sm.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            setControllers(sm.getActiveSessions(component))
        } catch (_: SecurityException) {
            // Acceso revocado entre la comprobación y la llamada.
        }
    }

    fun addListener(l: (Track?) -> Unit) {
        listeners += l
        l(track)
    }

    fun removeListener(l: (Track?) -> Unit) {
        listeners -= l
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != "com.android.bluetooth" }
        controllers.forEach { it.registerCallback(callback, main) }
        refresh()
    }

    /** Elige la sesión a mostrar: la que está sonando; si no, una en pausa con título (para poder reanudarla). */
    private fun refresh() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = controllers.firstOrNull {
            val st = it.playbackState?.state
            (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING) && it.metadata != null
        }
        current = playing ?: paused
        val c = current
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        track = if (c == null || md == null || title.isNullOrBlank()) null else Track(
            pkg = c.packageName,
            title = title,
            // Cada app rellena distinto: se toma el primer dato que no sea vacío ni repita el título.
            artist = listOf(
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                MediaMetadata.METADATA_KEY_ALBUM,
            ).mapNotNull { md.getString(it) }.firstOrNull { it.isNotBlank() && !it.equals(title, ignoreCase = true) } ?: "",
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
        listeners.toList().forEach { it(track) }
    }

    // ---------- Controles ----------

    fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = current?.transportControls?.skipToNext()

    fun previous() = current?.transportControls?.skipToPrevious()

    /** Detiene lo que suena. La app se queda en segundo plano; el sistema la libera cuando necesite la RAM. */
    fun stop() {
        current?.transportControls?.stop()
    }
}
