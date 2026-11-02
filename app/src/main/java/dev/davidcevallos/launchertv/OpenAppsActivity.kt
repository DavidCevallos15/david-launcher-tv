package dev.davidcevallos.launchertv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Apps abiertas, como las recientes del celular: OK continúa donde quedaste (si Android la conservó en RAM,
 * vuelve tal cual estaba) y ▲ la cierra para liberar memoria. Muestra cuánto ocupa cada una.
 */
class OpenAppsActivity : BaseActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: OpenAppsRepository
    private lateinit var tiles: TileFactory
    private lateinit var row: LinearLayout
    private lateinit var memText: TextView
    private lateinit var memBar: View
    private lateinit var emptyText: View
    private lateinit var panel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_apps)
        repo = OpenAppsRepository(this, AppRepository(this))
        tiles = TileFactory(this)
        row = findViewById(R.id.appsRow)
        memText = findViewById(R.id.memText)
        memBar = findViewById(R.id.memBar)
        emptyText = findViewById(R.id.emptyText)
        panel = findViewById(R.id.appsScroll)
        panel.background = Theme.glassDrawable(this)
        showWallpaperBackdrop(findViewById<ImageView>(R.id.backdrop), io, main)
    }

    override fun onResume() {
        super.onResume()
        reload(refocus = true)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun reload(refocus: Boolean) {
        try {
            io.execute {
                val list = repo.list()
                val mem = repo.memory()
                val access = repo.hasUsageAccess()
                main.post { if (!isFinishing && !isDestroyed) render(list, mem, access, refocus) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun render(list: List<OpenApp>, mem: Pair<Long, Long>, access: Boolean, refocus: Boolean) {
        val (free, total) = mem
        val used = (total - free).coerceAtLeast(0)
        memText.text = getString(R.string.open_apps_mem, free, total) +
            if (access) " · " + resources.getQuantityString(R.plurals.open_apps_count, list.size, list.size) else ""
        val frac = if (total > 0) used.toFloat() / total else 0f
        memBar.layoutParams = memBar.layoutParams.apply { width = (dpi(360f) * frac).toInt() }
        memBar.setBackgroundColor(if (frac > 0.85f) 0xFFF87171.toInt() else Theme.accent(this))

        val focusedIndex = row.indexOfChild(currentFocus) // para devolver el foco al mismo sitio tras recargar
        row.removeAllViews()
        if (!access) {
            // Sin el permiso especial Android no deja ver qué apps siguen abiertas: se ofrece activarlo.
            (emptyText as TextView).setText(R.string.open_apps_need_access)
            emptyText.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            val grant = tiles.actionTile(getString(R.string.grant_access), getDrawable(R.drawable.ic_settings), 220f) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.usage_access_manual, Toast.LENGTH_LONG).show()
                }
            }
            row.addView(grant.root)
            grant.root.requestFocus()
            return
        }
        (emptyText as TextView).setText(R.string.open_apps_empty)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        panel.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) return

        val w = (unzoomedWidthDp() * 0.2f * LauncherPrefs(this).tileScale / 100f).coerceIn(150f, 300f)
        val gap = dpi(24f)
        list.forEach { app ->
            val label = app.entry.label + if (app.inUse) " · en uso" else ""

            val tile = tiles.appTile(
                app.entry.copy(label = label), w, live = app.inUse,
            ) {
                try {
                    startActivity(app.entry.intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                }
            }
            tile.root.setOnKeyListener { v, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && ev.repeatCount == 0) {
                    closeCard(v, app.entry.packageName)
                    true
                } else false
            }
            row.addView(tile.root, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
        }
        val closeAll = tiles.actionTile(getString(R.string.close_all), getDrawable(R.drawable.ic_close), w) {
            list.forEach { repo.close(it.entry.packageName) }
            main.postDelayed({ reload(refocus = true) }, 900L)
        }
        row.addView(closeAll.root, LinearLayout.LayoutParams(-2, -2))
        val target = if (refocus || focusedIndex < 0) 0 else focusedIndex.coerceAtMost(row.childCount - 1)
        row.getChildAt(target)?.requestFocus()
    }

    /** Como deslizar hacia arriba en el celular: la tarjeta sube, se desvanece y la app se cierra. */
    private fun closeCard(card: View, pkg: String) {
        val index = row.indexOfChild(card)
        card.animate().translationY(-dpi(110f).toFloat()).alpha(0f).setDuration(220L).withEndAction {
            row.removeView(card)
            row.getChildAt(index.coerceAtMost(row.childCount - 1))?.requestFocus()
            if (row.childCount <= 1) {
                row.removeAllViews()
                emptyText.visibility = View.VISIBLE
                panel.visibility = View.GONE
            }
        }.start()
        repo.close(pkg)
        main.postDelayed({ reload(refocus = false) }, 1_200L)
    }
}
