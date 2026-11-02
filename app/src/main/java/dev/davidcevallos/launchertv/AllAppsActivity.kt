package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Grid de todas las apps lanzables. Las columnas y el tamaño de las tarjetas se calculan
 * según el ancho real de la pantalla, así nada se sale ni se superpone en TVs de 720p, 1080p o 4K.
 * OK largo sobre una app la fija / quita del inicio.
 */
class AllAppsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: AppRepository
    private lateinit var tiles: TileFactory
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.drawable.bg_fallback)
        repo = AppRepository(this)
        tiles = TileFactory(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD_DP), dp(40f), dp(PAD_DP), dp(56f))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.all_apps)
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.all_apps_hint)
            textSize = 15f
            setTextColor(0xB3FFFFFF.toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f); bottomMargin = dp(28f) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            addView(content)
        })

        // Cargar íconos de decenas de apps tarda: se hace fuera del hilo principal.
        io.execute {
            val entries = repo.allApps()
            val pinned = repo.dockPackages().toSet()
            main.post { if (!isFinishing && !isDestroyed) populate(entries, pinned) }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun populate(entries: List<AppEntry>, pinned: Set<String>) {
        val screenW = resources.configuration.screenWidthDp.toFloat()
        val avail = screenW - 2 * PAD_DP
        val cols = ((avail + GAP_DP) / (TARGET_TILE_DP + GAP_DP)).toInt().coerceIn(3, 7)
        val tileW = (avail - GAP_DP * (cols - 1)) / cols

        var first: View? = null
        entries.chunked(cols).forEach { rowEntries ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                clipChildren = false
                clipToPadding = false
            }
            rowEntries.forEach { entry ->
                lateinit var tile: TileFactory.Tile
                tile = tiles.appTile(
                    entry, tileW,
                    badge = entry.packageName in pinned,
                    onLongClick = {
                        val nowPinned = repo.togglePin(entry.packageName)
                        tile.setBadge(nowPinned)
                        val msg = if (nowPinned) R.string.pinned else R.string.unpinned
                        Toast.makeText(this, getString(msg, entry.label), Toast.LENGTH_SHORT).show()
                    },
                ) {
                    try {
                        startActivity(entry.intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    }
                }
                if (first == null) first = tile.root
                row.addView(tile.root, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(GAP_DP) })
            }
            content.addView(row, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16f) })
        }
        first?.requestFocus()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private companion object {
        const val PAD_DP = 64f
        const val GAP_DP = 24f
        const val TARGET_TILE_DP = 190f
    }
}
