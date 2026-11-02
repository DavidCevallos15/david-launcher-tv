package dev.davidcevallos.launchertv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Personalizar: fuentes y tamaño del reloj/fecha, qué widgets se ven, ciudad del clima, colores y comportamiento.
 * Cada fila se maneja con el control: ◀ ▶ (u OK) cambian el valor y arriba se ve el resultado al instante.
 */
class CustomizeActivity : BaseActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var header: HeaderController
    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customize)
        prefs = LauncherPrefs(this)
        header = HeaderController(this, findViewById(R.id.header))
        list = findViewById(R.id.list)
        findViewById<View>(R.id.panel).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC0B1020.toInt(), 0xE6060914.toInt()),
        ).apply {
            cornerRadius = dp(28f).toFloat()
            setStroke(dp(1f), 0x40FFFFFF)
        }
        showWallpaperBackdrop(findViewById<ImageView>(R.id.backdrop), io, main)
        buildRows(restoreRow = savedInstanceState?.getInt("row", -1) ?: -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("row", list.indexOfChild(currentFocus))
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        header.start()
        // Al volver de Ajustes (permiso de notificaciones) o de Secciones, repinta los estados.
        if (resumedOnce) buildRows(restoreRow = list.indexOfChild(currentFocus))
        resumedOnce = true
    }

    override fun onPause() {
        super.onPause()
        header.stop()
    }

    override fun onDestroy() {
        header.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- Filas ----------

    private fun buildRows(restoreRow: Int = -1) {
        list.removeAllViews()

        section("RELOJ Y FECHA")
        choice("Fuente del reloj", Theme.FONTS, { prefs.clockFont }, { prefs.clockFont = it }, fonts = true)
        choice("Tamaño del reloj", Theme.CLOCK_SIZES, { prefs.clockSize }, { prefs.clockSize = it })
        choice("Fuente de fecha y saludo", Theme.FONTS, { prefs.dateFont }, { prefs.dateFont = it }, fonts = true)
        choice("Formato de hora", Theme.HOUR_FORMATS, { prefs.hourFormat }, { prefs.hourFormat = it })

        section("WIDGETS")
        toggle("Reloj", { prefs.showClock }, { prefs.showClock = it })
        toggle("Fecha", { prefs.showDate }, { prefs.showDate = it })
        toggle("Saludo según la hora", { prefs.showGreeting }, { prefs.showGreeting = it })
        toggle("Clima en tiempo real", { prefs.showWeather }, {
            prefs.showWeather = it
            if (it) header.reloadWeather()
        })
        val cities = listOf(Option("auto", "Automática (por internet)")) +
            WeatherRepository.CITIES.map { Option(it.id, it.name) }
        choice("Ciudad del clima", cities, { prefs.weatherCity }, { prefs.weatherCity = it }, after = { header.reloadWeather() })
        choice(
            "Unidades", listOf(Option("c", "Celsius °C"), Option("f", "Fahrenheit °F")),
            { if (prefs.fahrenheit) "f" else "c" }, { prefs.fahrenheit = it == "f" }, after = { header.reloadWeather() },
        )

        section("REPRODUCTOR (música en segundo plano)")
        toggle("Mostrar qué suena y sus controles", { prefs.showPlayer }, { prefs.showPlayer = it })
        val playerRow = SettingRow(this, "Permiso para controlar la música", arrows = false)
        fun refreshAccess() {
            val ok = MediaHub.hasAccess(this)
            playerRow.value.text = if (ok) "Activo ✓" else "Falta · OK para activarlo"
            playerRow.value.setTextColor(if (ok) Theme.accent(this) else 0xFFFBBF24.toInt())
        }
        refreshAccess()
        playerRow.onStep = { openNotificationAccess() }
        list.addView(playerRow.view)

        section("COLORES")
        choice("Color de acento", Theme.COLORS, { prefs.accent }, { prefs.accent = it }, colors = true)
        choice("Color del reloj y la fecha", Theme.COLORS, { prefs.clockColor }, { prefs.clockColor = it }, colors = true)
        choice("Estilo de las tarjetas de widgets", Theme.GLASS, { prefs.glass }, { prefs.glass = it })

        section("TAMAÑOS")
        choice(
            "Zoom de toda la interfaz", (70..130 step 10).map { Option(it.toString(), "$it %") },
            { prefs.uiZoom.toString() }, { prefs.uiZoom = it.toInt() }, after = { recreate() },
        )
        choice(
            "Tamaño de las tarjetas del menú", (80..130 step 10).map { Option(it.toString(), "$it %") },
            { prefs.tileScale.toString() }, { prefs.tileScale = it.toInt() },
        )

        section("MENÚ")
        action("Secciones (Streaming, Música, Juegos…)", "OK para editar") {
            startActivity(android.content.Intent(this, SectionsActivity::class.java))
        }
        action("Organizar apps del inicio", "OK para empezar") {
            prefs.pendingMoveMode = true
            finish()
        }

        section("COMPORTAMIENTO")
        toggle("Saludo de bienvenida al iniciar", { prefs.welcome }, { prefs.welcome = it })
        choice(
            "Ocultar el menú solo", Theme.AUTO_HIDE,
            { prefs.autoHideSeconds.toString() }, { prefs.autoHideSeconds = it.toInt() },
        )
        action("Restablecer estilo", "OK") {
            prefs.resetStyle()
            header.applyStyle()
            val focusedIndex = list.indexOfChild(currentFocus)
            buildRows()
            list.getChildAt(focusedIndex.coerceAtLeast(1))?.requestFocus()
        }

        (list.getChildAt(if (restoreRow > 0) restoreRow else 1))?.requestFocus()
    }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title
            textSize = 13f
            letterSpacing = 0.15f
            setTextColor(0x99FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f); bottomMargin = dp(2f); marginStart = dp(12f) })
    }

    private fun applied(after: (() -> Unit)? = null) {
        header.applyStyle()
        after?.invoke()
    }

    private fun choice(
        label: String,
        options: List<Option>,
        get: () -> String,
        set: (String) -> Unit,
        fonts: Boolean = false,
        colors: Boolean = false,
        after: (() -> Unit)? = null,
    ) {
        fun current(): Option = options.firstOrNull { it.id == get() } ?: options[0]
        val row = SettingRow(this, label)
        fun refresh() {
            val o = current()
            row.value.text = o.label
            row.value.setTextColor(if (colors && o is ColorOption) o.color else 0xFFFFFFFF.toInt())
            row.value.typeface = if (fonts && o is FontOption) o.typeface(this) else Typeface.DEFAULT
        }
        row.onStep = { dir ->
            val i = options.indexOf(current())
            set(options[(i + dir + options.size) % options.size].id)
            refresh()
            applied(after)
        }
        refresh()
        list.addView(row.view)
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = SettingRow(this, label)
        fun refresh() {
            row.value.text = if (get()) "Sí" else "No"
            row.value.setTextColor(if (get()) Theme.accent(this) else 0x99FFFFFF.toInt())
        }
        row.onStep = {
            set(!get())
            refresh()
            applied()
        }
        refresh()
        list.addView(row.view)
    }

    private fun action(label: String, hint: String, onClick: () -> Unit) {
        val row = SettingRow(this, label, arrows = false)
        row.value.text = hint
        row.value.setTextColor(0x99FFFFFF.toInt())
        row.onStep = { onClick() }
        list.addView(row.view)
    }

    /** Abre la pantalla del sistema donde se concede el acceso a notificaciones (necesario para ver y controlar la música). */
    private fun openNotificationAccess() {
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, "Actívalo en Ajustes › Apps › Acceso especial › Acceso a notificaciones › David Launcher TV",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
