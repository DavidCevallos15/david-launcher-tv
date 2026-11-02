package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.TimeZone

/** Fondo degradado generado en código: viene con la app, pesa cero y se ve bien en cualquier TV. */
class WallpaperPreset(
    val id: String,
    val name: String,
    val c0: Int,
    val c1: Int,
    val c2: Int,
    val glow: Int,
    val gx: Float,
    val gy: Float,
)

class WallpaperItem(
    val id: String,
    val name: String,
    val kind: Kind,
    val file: File? = null,
    val preset: WallpaperPreset? = null,
) {
    enum class Kind { PRESET, IMAGE, VIDEO }
}

/**
 * Catálogo de fondos (los de la carpeta de la app + los degradados integrados) y la selección actual.
 *
 *   /sdcard/Android/data/<applicationId>/files/wallpapers/
 *
 * Sin permisos de almacenamiento; los archivos nunca forman parte del APK ni del repo.
 */
class WallpaperStore(context: Context) {

    private val dir: File? = context.getExternalFilesDir(LauncherConfig.WALLPAPER_DIR)
    private val prefs = LauncherPrefs(context)

    val selectedId: String get() = prefs.wallpaperId

    fun select(id: String) {
        prefs.wallpaperId = id
    }

    /** Archivos del usuario, ordenados por nombre. */
    fun files(): List<WallpaperItem> =
        dir?.listFiles { f -> f.isFile && (isImage(f) || isVideo(f)) }
            ?.sortedBy { it.name }
            ?.map { f ->
                WallpaperItem(
                    id = "file:${f.name}",
                    name = prettyName(f),
                    kind = if (isVideo(f)) WallpaperItem.Kind.VIDEO else WallpaperItem.Kind.IMAGE,
                    file = f,
                )
            }.orEmpty()

    fun presets(): List<WallpaperItem> = PRESETS.map {
        WallpaperItem("preset:${it.id}", it.name, WallpaperItem.Kind.PRESET, preset = it)
    }

    /** Todo lo elegible: primero los archivos propios, luego los degradados integrados. */
    fun all(): List<WallpaperItem> = files() + presets()

    /** Fondo del día: índice = día epoch % cantidad (estable todo el día, sin estado guardado). */
    fun autoPick(): WallpaperItem? {
        val pool = files().ifEmpty { presets() }
        if (pool.isEmpty()) return null
        return pool[(epochDay() % pool.size).toInt()]
    }

    /** Lo que hay que mostrar ahora: la elección del usuario o, en automático, el fondo del día. */
    fun current(): WallpaperItem? {
        val sel = prefs.wallpaperId
        if (sel != AUTO) all().firstOrNull { it.id == sel }?.let { return it }
        return autoPick()
    }

    /** Carga el fondo (o su miniatura) ya escalado. Llamar fuera del hilo principal. */
    fun load(item: WallpaperItem, maxW: Int, maxH: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? =
        runCatching {
            when (item.kind) {
                WallpaperItem.Kind.PRESET -> {
                    val w = minOf(maxW, 960)
                    renderPreset(item.preset!!, w, w * 9 / 16)
                }
                WallpaperItem.Kind.IMAGE -> decodeSampled(item.file!!, maxW, maxH, config)
                WallpaperItem.Kind.VIDEO -> videoFrame(item.file!!, maxW)
            }
        }.getOrNull()

    private fun renderPreset(p: WallpaperPreset, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // 8888: en RGB_565 los degradados hacen bandas
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(p.c0, p.c1, p.c2), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = RadialGradient(
            p.gx * w, p.gy * h, maxOf(w, h) * 0.75f,
            intArrayOf((p.glow and 0x00FFFFFF) or (0x88 shl 24), p.glow and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    private fun decodeSampled(file: File, maxW: Int, maxH: Int, config: Bitmap.Config): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun videoFrame(file: File, maxW: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            if (frame.width <= maxW) return frame
            val scaled = Bitmap.createScaledBitmap(frame, maxW, frame.height * maxW / frame.width, true)
            if (scaled !== frame) frame.recycle()
            return scaled
        } finally {
            r.release()
        }
    }

    private fun prettyName(f: File): String {
        val base = f.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
        return if (base.all { it.isDigit() }) "Fondo $base" else base.replaceFirstChar { it.uppercase() }
    }

    private fun isImage(f: File) = f.extension.lowercase() in LauncherConfig.IMAGE_EXT
    private fun isVideo(f: File) = f.extension.lowercase() in LauncherConfig.VIDEO_EXT

    /** Día actual en hora local, contado desde 1970 (equivale a LocalDate.toEpochDay, que pide API 26). */
    private fun epochDay(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L
    }

    companion object {
        const val AUTO = "auto"

        val PRESETS = listOf(
            WallpaperPreset("aurora", "Aurora", 0xFF04121F.toInt(), 0xFF0B3B4A.toInt(), 0xFF2B1B5E.toInt(), 0xFF22D3A6.toInt(), 0.25f, 0.85f),
            WallpaperPreset("atardecer", "Atardecer", 0xFF1A0B2E.toInt(), 0xFF7A1F5C.toInt(), 0xFFF2763A.toInt(), 0xFFFFC857.toInt(), 0.80f, 0.95f),
            WallpaperPreset("oceano", "Océano", 0xFF020B1F.toInt(), 0xFF053B73.toInt(), 0xFF0E7490.toInt(), 0xFF38BDF8.toInt(), 0.70f, 0.20f),
            WallpaperPreset("bosque", "Bosque", 0xFF04120C.toInt(), 0xFF0F3D2B.toInt(), 0xFF1F6B45.toInt(), 0xFFA3E635.toInt(), 0.20f, 0.30f),
            WallpaperPreset("medianoche", "Medianoche", 0xFF05060F.toInt(), 0xFF121A3A.toInt(), 0xFF2A1B5E.toInt(), 0xFF818CF8.toInt(), 0.50f, 0.10f),
            WallpaperPreset("ambar", "Ámbar", 0xFF140A03.toInt(), 0xFF4A2308.toInt(), 0xFF9A4A0B.toInt(), 0xFFFBBF24.toInt(), 0.85f, 0.15f),
            WallpaperPreset("neon", "Neón", 0xFF0A0214.toInt(), 0xFF3B0A5C.toInt(), 0xFF9D174D.toInt(), 0xFFF472B6.toInt(), 0.15f, 0.80f),
            WallpaperPreset("grafito", "Grafito", 0xFF0A0A0B.toInt(), 0xFF18181B.toInt(), 0xFF27272A.toInt(), 0xFF71717A.toInt(), 0.50f, 0.50f),
        )
    }
}
