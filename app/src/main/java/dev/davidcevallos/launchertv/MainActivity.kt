package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var dock: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: View
    private lateinit var greetingSmall: TextView
    private lateinit var wallpapers: WallpaperRotator
    private lateinit var tiles: TileFactory
    private lateinit var apps: AppRepository

    private val main = Handler(Looper.getMainLooper())
    private val locale = Locale("es", "EC")
    private lateinit var timeFmt: SimpleDateFormat
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", locale)
    private var lastFocusIndex = 0

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFmt.format(now)
            date.text = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
            main.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dock = findViewById(R.id.dock)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        greeting = findViewById(R.id.greeting)
        greetingSmall = findViewById(R.id.greetingSmall)
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
        // Respeta el formato 12/24 h del TV (puede haber cambiado mientras estabas en Ajustes).
        timeFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", locale)
        buildDock() // barato: pocas tarjetas; así reflejas instalaciones/desinstalaciones sin listeners
        main.post(clockTick)
        wallpapers.start()
        showGreeting()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockTick)
        main.removeCallbacks(hideGreeting)
        wallpapers.stop()
    }

    override fun onDestroy() {
        wallpapers.release()
        super.onDestroy()
    }

    /** Un launcher no "sale": Back en Home no hace nada. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    // ---------- Bienvenida ----------

    /** Solo la primera vez que aparece Home tras arrancar el TV (el proceso sigue vivo al volver de otras apps). */
    private fun showGreeting() {
        if (greeted) return
        greeted = true
        greetingSmall.setText(
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.good_morning
                in 12..18 -> R.string.good_afternoon
                else -> R.string.good_night
            },
        )
        greeting.visibility = View.VISIBLE
        greeting.alpha = 0f
        greeting.animate().alpha(1f).setDuration(700L).start()
        main.postDelayed(hideGreeting, 3_200L)
    }

    private val hideGreeting = Runnable {
        greeting.animate().alpha(0f).setDuration(900L)
            .withEndAction { greeting.visibility = View.GONE }.start()
    }

    // ---------- Dock ----------

    private fun tileWidthDp(): Float = (resources.configuration.screenWidthDp * 0.18f).coerceIn(170f, 250f)

    private fun buildDock() {
        dock.removeAllViews()
        val gap = dp(24f)
        val w = tileWidthDp()

        apps.dockApps().forEach { entry ->
            val tile = tiles.appTile(
                entry, w,
                onLongClick = {
                    apps.unpin(entry.packageName)
                    Toast.makeText(this, getString(R.string.unpinned, entry.label), Toast.LENGTH_SHORT).show()
                    buildDock()
                },
            ) { launch(entry.intent) }
            dock.addSpaced(tile.root, gap)
        }

        dock.addSpaced(
            tiles.actionTile(getString(R.string.all_apps), getDrawable(R.drawable.ic_grid), w) {
                startActivity(Intent(this, AllAppsActivity::class.java))
            }.root, gap,
        )
        dock.addSpaced(
            tiles.actionTile(getString(R.string.wallpapers), getDrawable(R.drawable.ic_wallpaper), w) {
                startActivity(Intent(this, WallpaperActivity::class.java))
            }.root, gap,
        )
        dock.addSpaced(
            tiles.actionTile(getString(R.string.settings), getDrawable(R.drawable.ic_settings), w) {
                launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.root, gap,
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

    private companion object {
        /** Vive mientras viva el proceso: se resetea al reiniciar el TV, no al volver de otra app. */
        @Volatile
        var greeted = false
    }
}
