package dev.davidcevallos.launchertv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Selector de fondos. El fondo de pantalla completa muestra en vivo el que tienes enfocado;
 * OK lo aplica y vuelve a Home. "Automático" rota un fondo distinto cada día.
 */
class WallpaperActivity : Activity() {

    private val io = Executors.newFixedThreadPool(2)            // miniaturas
    private val previewIo = Executors.newSingleThreadExecutor() // vista previa: no espera detrás de las miniaturas
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: WallpaperStore
    private lateinit var backdrop: ImageView
    private lateinit var nameView: TextView
    private lateinit var subtitleView: TextView

    private var previewToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpapers)
        backdrop = findViewById(R.id.backdrop)
        nameView = findViewById(R.id.wpName)
        subtitleView = findViewById(R.id.wpSubtitle)
        store = WallpaperStore(this)

        val tiles = TileFactory(this)
        val row = findViewById<LinearLayout>(R.id.wpRow)
        val all = store.all()
        // Si el fondo guardado ya no existe (borraste el archivo), el efectivo es el automático.
        val selected = store.selectedId.takeIf { id -> all.any { it.id == id } } ?: WallpaperStore.AUTO
        val tileW = (resources.configuration.screenWidthDp * 0.17f).coerceIn(160f, 240f)
        val gap = dp(24f)
        var focusTarget: android.view.View? = null
        var focusIndex = 0
        val thumbJobs = ArrayList<Triple<Int, WallpaperItem, TileFactory.Tile>>()

        val autoPick = store.autoPick()
        val entries = buildList {
            add(Entry(WallpaperStore.AUTO, getString(R.string.wallpaper_auto), getString(R.string.wallpaper_auto_desc), autoPick))
            all.forEach { add(Entry(it.id, it.name, kindLabel(it), it)) }
        }

        entries.forEachIndexed { index, e ->
            val tile = tiles.mediaTile(e.name, tileW, badge = e.id == selected) {
                store.select(e.id)
                Toast.makeText(this, getString(R.string.wallpaper_applied, e.name), Toast.LENGTH_SHORT).show()
                finish()
            }
            val prevFocus = tile.root.onFocusChangeListener
            tile.root.setOnFocusChangeListener { v, has ->
                prevFocus?.onFocusChange(v, has)
                if (has) showPreview(e)
            }
            row.addView(tile.root, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            if (e.id == selected) { focusTarget = tile.root; focusIndex = index }
            e.item?.let { item -> thumbJobs += Triple(index, item, tile) }
        }
        // Primero las miniaturas más cercanas a donde empieza el foco; el resto se rellena mientras recorres.
        thumbJobs.sortedBy { kotlin.math.abs(it.first - focusIndex) }.forEach { (_, item, tile) ->
            loadThumb(item) { bmp -> tile.setImage(BitmapDrawable(resources, bmp)) }
        }
        (focusTarget ?: row.getChildAt(0))?.requestFocus()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        previewIo.shutdownNow()
        super.onDestroy()
    }

    private class Entry(val id: String, val name: String, val subtitle: String, val item: WallpaperItem?)

    private fun kindLabel(item: WallpaperItem) = getString(
        when (item.kind) {
            WallpaperItem.Kind.PRESET -> R.string.wallpaper_kind_preset
            WallpaperItem.Kind.IMAGE -> R.string.wallpaper_kind_image
            WallpaperItem.Kind.VIDEO -> R.string.wallpaper_kind_video
        },
    )

    private fun loadThumb(item: WallpaperItem, onLoaded: (Bitmap) -> Unit) {
        io.execute {
            val bmp = store.load(item, 320, 180) ?: return@execute
            main.post { if (!isFinishing && !isDestroyed) onLoaded(bmp) }
        }
    }

    /** Vista previa a pantalla completa del fondo enfocado, con un pequeño debounce al recorrer rápido. */
    private fun showPreview(e: Entry) {
        nameView.text = e.name
        subtitleView.text = e.subtitle
        val item = e.item ?: return
        val token = ++previewToken
        main.removeCallbacks(previewRunnable)
        previewRunnable = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            try {
                previewIo.execute {
                    val bmp = store.load(item, 1280, 720) ?: return@execute
                    main.post {
                        if (token != previewToken || isFinishing || isDestroyed) return@post
                        backdrop.setImageBitmap(bmp)
                        backdrop.alpha = 0.2f
                        backdrop.animate().alpha(1f).setDuration(350L).start()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // La pantalla se cerró entre el debounce y la carga.
            }
        }
        main.postDelayed(previewRunnable, 150L)
    }

    private var previewRunnable = Runnable {}

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
