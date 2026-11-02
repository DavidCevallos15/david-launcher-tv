package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Pinta el fondo elegido en [WallpaperStore] (o el del día en modo automático):
 * imágenes y degradados con crossfade entre dos ImageView, videos en loop (mudos)
 * sobre un TextureView que entra y sale con fade.
 *
 * Se vuelve a evaluar en cada onResume, así el cambio hecho en el selector de fondos
 * se ve al volver a Home. El video se pausa cuando el launcher no está visible:
 * dentro de Netflix o YouTube no consume decodificador ni CPU.
 */
class WallpaperRotator(
    context: Context,
    private val front: ImageView,
    private val back: ImageView,
    private val video: TextureView,
) {
    private val store = WallpaperStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var currentId: String? = null
    private var currentBitmap: Bitmap? = null

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var visible = false
    private var pendingVideo: WallpaperItem? = null
    private var videoW = 0
    private var videoH = 0
    private var videoRetries = 0

    private val midnightTick = Runnable { refresh(); scheduleMidnight() }

    init {
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                pendingVideo?.let { playVideo(it) }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCenterCrop()

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                currentId = null // forzar recarga cuando vuelva la superficie
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun start() {
        visible = true
        refresh()
        scheduleMidnight()
        if (prepared) player?.takeIf { !it.isPlaying }?.start()
    }

    fun stop() {
        visible = false
        main.removeCallbacks(midnightTick)
        if (prepared) player?.takeIf { it.isPlaying }?.pause()
    }

    fun release() {
        stop()
        releasePlayer()
        io.shutdownNow()
    }

    /** Elige el fondo en segundo plano (listar la carpeta es lento en el TV) y lo aplica en el hilo principal. */
    fun refresh() {
        try {
            io.execute {
                val item = store.current() ?: return@execute // sin nada que mostrar se queda el gradiente de fallback
                main.post { if (visible) apply(item) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // El launcher se cerró entre medias.
        }
    }

    private fun apply(item: WallpaperItem) {
        if (item.id == currentId) {
            // Mismo fondo: si es un video que se quedó sin reproductor (decodificador ocupado, superficie nueva), revívelo.
            if (item.kind == WallpaperItem.Kind.VIDEO && player == null && pendingVideo == null) playVideo(item)
            return
        }
        currentId = item.id
        videoRetries = 0
        if (item.kind == WallpaperItem.Kind.VIDEO) playVideo(item) else showImage(item)
    }

    // ---------- Imagen ----------

    private fun showImage(item: WallpaperItem) {
        io.execute {
            val bmp = store.load(
                item, LauncherConfig.MAX_WALLPAPER_W, LauncherConfig.MAX_WALLPAPER_H,
                if (item.kind == WallpaperItem.Kind.PRESET) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
            ) ?: return@execute
            main.post {
                if (currentId != item.id) { bmp.recycle(); return@post }
                if (video.alpha > 0f) {
                    // Venimos de un video: la imagen va debajo y el video se desvanece encima.
                    setImage(bmp)
                    video.animate().alpha(0f).setDuration(LauncherConfig.CROSSFADE_MS)
                        .withEndAction { releasePlayer() }.start()
                } else {
                    crossfadeImage(bmp)
                }
            }
        }
    }

    private fun crossfadeImage(bmp: Bitmap) {
        val old = currentBitmap
        currentBitmap = bmp
        back.animate().cancel()
        back.setImageBitmap(bmp)
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(LauncherConfig.CROSSFADE_MS).withEndAction {
            front.setImageBitmap(bmp)
            back.setImageDrawable(null)
            back.alpha = 0f
            if (old != null && old !== bmp && !old.isRecycled) old.recycle()
        }.start()
    }

    private fun setImage(bmp: Bitmap) {
        val old = currentBitmap
        currentBitmap = bmp
        front.setImageBitmap(bmp)
        if (old != null && old !== bmp && !old.isRecycled) old.recycle()
    }

    // ---------- Video ----------

    private fun playVideo(item: WallpaperItem) {
        Log.d(TAG, "playVideo ${item.id} surface=${surface != null}")
        val s = surface
        if (s == null) { pendingVideo = item; return }
        pendingVideo = null
        releasePlayer()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(s)
            mp.setDataSource(item.file!!.absolutePath)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                prepared = true
                videoW = it.videoWidth
                videoH = it.videoHeight
                applyCenterCrop()
                if (visible) it.start()
                video.animate().alpha(1f).setDuration(LauncherConfig.CROSSFADE_MS).withEndAction {
                    // Con el video cubriendo todo, la imagen de abajo solo gasta RAM.
                    front.setImageDrawable(null)
                    currentBitmap?.takeIf { b -> !b.isRecycled }?.recycle()
                    currentBitmap = null
                }.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "video error what=$what extra=$extra (${item.id})")
                videoFailed(item)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "video no se pudo abrir (${item.id})", e)
            videoFailed(item)
        }
    }

    /** Otra app (Netflix, Disney+...) puede tener ocupado el decodificador justo al volver a Home: se reintenta. */
    private fun videoFailed(item: WallpaperItem) {
        releasePlayer()
        video.alpha = 0f
        if (videoRetries++ < 3) {
            main.postDelayed({
                if (visible && currentId == item.id && player == null) playVideo(item)
            }, 1_500L)
        }
    }

    private fun releasePlayer() {
        prepared = false
        player?.run {
            setOnPreparedListener(null)
            setOnErrorListener(null)
            runCatching { stop() }
            release()
        }
        player = null
    }

    /** TextureView estira el video al tamaño de la vista; esto lo convierte en center-crop. */
    private fun applyCenterCrop() {
        val vw = video.width.toFloat()
        val vh = video.height.toFloat()
        if (vw == 0f || vh == 0f || videoW == 0 || videoH == 0) return
        val scale = maxOf(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        video.setTransform(m)
    }

    // ---------- Utilidades ----------

    private fun scheduleMidnight() {
        main.removeCallbacks(midnightTick)
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        main.postDelayed(midnightTick, next.timeInMillis - System.currentTimeMillis() + 2_000)
    }

    private companion object {
        const val TAG = "WallpaperRotator"
    }
}
