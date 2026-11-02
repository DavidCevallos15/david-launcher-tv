package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var dock: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var wallpapers: WallpaperRotator
    private lateinit var tiles: TileFactory
    private lateinit var apps: AppRepository

    private val main = Handler(Looper.getMainLooper())
    private val locale = Locale("es", "EC")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", locale)
    private var lastFocusIndex = 0

    private val clockTick = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now()
            clock.text = now.format(timeFmt)
            date.text = now.format(dateFmt).replaceFirstChar { it.titlecase(locale) }
            main.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dock = findViewById(R.id.dock)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        tiles = TileFactory(this)
        apps = AppRepository(this)
        wallpapers = WallpaperRotator(
            this,
            findViewById<ImageView>(R.id.wallFront),
            findViewById<ImageView>(R.id.wallBack),
            findViewById<TextureView>(R.id.wallVideo),
        )
    }

    override fun onResume() {
        super.onResume()
        buildDock() // barato: 4–6 tarjetas; así reflejas instalaciones/desinstalaciones sin listeners
        main.post(clockTick)
        wallpapers.start()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockTick)
        wallpapers.stop()
    }

    override fun onDestroy() {
        wallpapers.release()
        super.onDestroy()
    }

    /** Un launcher no "sale": Back en Home no hace nada. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun buildDock() {
        dock.removeAllViews()
        val gap = dp(28f)

        val entries = apps.dockApps()
        entries.forEach { entry ->
            dock.addSpaced(tiles.appTile(entry) { launch(entry.intent) }, gap)
        }

        dock.addSpaced(
            tiles.actionTile(getString(R.string.all_apps), getDrawable(R.drawable.ic_grid)) {
                startActivity(Intent(this, AllAppsActivity::class.java))
            }, gap,
        )
        dock.addSpaced(
            tiles.actionTile(getString(R.string.settings), getDrawable(R.drawable.ic_settings)) {
                launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, gap,
        )

        val count = dock.childCount
        dock.getChildAt(lastFocusIndex.coerceIn(0, count - 1))?.requestFocus()
    }

    private fun LinearLayout.addSpaced(view: View, gap: Int) {
        val index = childCount
        view.setOnFocusChangeListener(chain(view.onFocusChangeListener) { lastFocusIndex = index })
        addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = gap })
    }

    private fun chain(
        original: View.OnFocusChangeListener?,
        onFocus: () -> Unit,
    ) = View.OnFocusChangeListener { v, has ->
        original?.onFocusChange(v, has)
        if (has) onFocus()
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
