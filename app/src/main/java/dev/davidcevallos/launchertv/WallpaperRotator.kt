package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Pinta el fondo elegido en [WallpaperStore] (o el del día en modo automático): imágenes y degradados con
 * crossfade entre dos ImageView, y videos en loop (mudos) sobre un [SurfaceView].
 *
 * El video va en un SurfaceView, no en un TextureView: el decodificador de hardware escribe directo a una capa del
 * compositor y la GPU del TV no tiene que copiar ni mezclar cada fotograma (con TextureView el launcher gastaba ~60 %
 * de CPU y dibujaba a ~8 fps). El SurfaceView solo existe mientras el fondo es un video: con una imagen está GONE y
 * no gasta ni decodificador ni memoria. Se vuelve a evaluar en cada onResume; el video se pausa al salir de Home.
 */
class WallpaperRotator(
    context: Context,
    private val front: ImageView,
    private val back: ImageView,
    private val video: SurfaceView,
) {
    private val store = WallpaperStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var currentId: String? = null
    private var currentIsVideo = false
    private var currentBitmap: Bitmap? = null

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var visible = false
    private var pendingVideo: WallpaperItem? = null
    private var videoRetries = 0

    private val midnightTick = Runnable { refresh(); scheduleMidnight() }

    init {
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                pendingVideo?.let { playVideo(it) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releasePlayer()
                surface = null
                // Al volver a Home la superficie es nueva: hay que reiniciar el video (con una imagen no pasa nada).
                if (currentIsVideo) currentId = null
            }
        })
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
            if (item.kind == WallpaperItem.Kind.VIDEO && player == null && pendingVideo == null) playVideo(item)
            return
        }
        currentId = item.id
        videoRetries = 0
        if (item.kind == WallpaperItem.Kind.VIDEO) {
            currentIsVideo = true
            playVideo(item)
        } else {
            currentIsVideo = false
            releasePlayer()
            video.visibility = View.GONE // libera la superficie y el decodificador
            showImage(item)
        }
    }

    // ---------- Imagen ----------

    private fun showImage(item: WallpaperItem) {
        try {
            io.execute {
                val bmp = store.load(
                    item, LauncherConfig.MAX_WALLPAPER_W, LauncherConfig.MAX_WALLPAPER_H,
                    if (item.kind == WallpaperItem.Kind.PRESET) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
                ) ?: return@execute
                main.post {
                    if (currentId != item.id) { bmp.recycle(); return@post }
                    crossfadeImage(bmp)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
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

    // ---------- Video ----------

    private fun playVideo(item: WallpaperItem) {
        // Con un video encima, la imagen de abajo solo gasta RAM.
        front.setImageDrawable(null)
        currentBitmap?.takeIf { !it.isRecycled }?.recycle()
        currentBitmap = null

        val s = surface
        if (s == null) {
            pendingVideo = item
            video.visibility = View.VISIBLE // al hacerse visible el sistema crea la superficie -> surfaceCreated
            return
        }
        pendingVideo = null
        releasePlayer()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(s)
            mp.setDataSource(item.file!!.absolutePath)
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) // center-crop sin matrices
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                prepared = true
                if (visible) it.start()
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

    /** Otra app puede tener ocupado el decodificador justo al volver a Home: se reintenta unas veces. */
    private fun videoFailed(item: WallpaperItem) {
        releasePlayer()
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
