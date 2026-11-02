package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * Rota el fondo una vez al día: imágenes con crossfade entre dos ImageView,
 * videos en loop (mudos) sobre un TextureView que entra y sale con fade.
 *
 * Los fondos viven en el almacenamiento propio de la app:
 *   /sdcard/Android/data/<applicationId>/files/wallpapers/
 * sin permisos de almacenamiento, y nunca forman parte del APK ni del repo.
 *
 * El video se pausa cuando el launcher no está visible: dentro de Netflix o YouTube
 * no consume decodificador ni CPU.
 */
class WallpaperRotator(
    context: Context,
    private val front: ImageView,
    private val back: ImageView,
    private val video: TextureView,
) {
    private val dir: File? = context.getExternalFilesDir(LauncherConfig.WALLPAPER_DIR)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var currentFile: File? = null
    private var currentBitmap: Bitmap? = null

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var visible = false
    private var pendingVideo: File? = null
    private var videoW = 0
    private var videoH = 0

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
                currentFile = null // forzar recarga cuando vuelva la superficie
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

    /** Fondo del día: índice = día epoch % cantidad (estable todo el día, sin estado guardado). */
    fun refresh() {
        val files = dir?.listFiles { f -> f.isFile && (isImage(f) || isVideo(f)) }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return // se queda el gradiente de fallback

        val pick = files[(LocalDate.now().toEpochDay() % files.size).toInt()]
        if (pick == currentFile) return
        currentFile = pick

        if (isVideo(pick)) playVideo(pick) else showImage(pick)
    }

    // ---------- Imagen ----------

    private fun showImage(file: File) {
        io.execute {
            val bmp = decodeSampled(file) ?: return@execute
            main.post {
                if (currentFile != file) { bmp.recycle(); return@post }
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

    private fun playVideo(file: File) {
        val s = surface
        if (s == null) { pendingVideo = file; return }
        pendingVideo = null
        releasePlayer()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(s)
            mp.setDataSource(file.absolutePath)
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
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                video.alpha = 0f
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            video.alpha = 0f
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
        val now = LocalDateTime.now()
        val next = now.toLocalDate().plusDays(1).atStartOfDay()
        main.postDelayed(midnightTick, Duration.between(now, next).toMillis() + 2_000)
    }

    private fun decodeSampled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= LauncherConfig.MAX_WALLPAPER_W &&
            bounds.outHeight / (sample * 2) >= LauncherConfig.MAX_WALLPAPER_H
        ) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // la mitad de RAM que ARGB_8888; un fondo no necesita alfa
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    private fun isImage(f: File) = f.extension.lowercase() in LauncherConfig.IMAGE_EXT
    private fun isVideo(f: File) = f.extension.lowercase() in LauncherConfig.VIDEO_EXT
}
