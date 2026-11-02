package dev.davidcevallos.launchertv

import android.app.Activity
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
class CustomizeActivity : Activity() {

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
        loadBackdrop()
        buildRows()
    }

    override fun onResume() {
        super.onResume()
        header.start()
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

    private fun loadBackdrop() {
        val view = findViewById<ImageView>(R.id.backdrop)
        io.execute {
            val store = WallpaperStore(this)
            val bmp = store.current()?.let { store.load(it, 1280, 720) } ?: return@execute
            main.post { if (!isFinishing && !isDestroyed) view.setImageBitmap(bmp) }
        }
    }

    // ---------- Filas ----------

    private fun buildRows() {
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

        section("COLORES")
        choice("Color de acento", Theme.COLORS, { prefs.accent }, { prefs.accent = it }, colors = true)
        choice("Color del reloj y la fecha", Theme.COLORS, { prefs.clockColor }, { prefs.clockColor = it }, colors = true)
        choice("Estilo de las barras", Theme.GLASS, { prefs.glass }, { prefs.glass = it })

        section("COMPORTAMIENTO")
        toggle("Saludo de bienvenida al iniciar", { prefs.welcome }, { prefs.welcome = it })
        choice(
            "Ocultar el menú solo", Theme.AUTO_HIDE,
            { prefs.autoHideSeconds.toString() }, { prefs.autoHideSeconds = it.toInt() },
        )
        action("Organizar apps del inicio", "OK para empezar") {
            prefs.pendingMoveMode = true
            finish()
        }
        action("Restablecer estilo", "OK") {
            prefs.resetStyle()
            header.applyStyle()
            val focusedIndex = list.indexOfChild(currentFocus)
            buildRows()
            list.getChildAt(focusedIndex.coerceAtLeast(1))?.requestFocus()
        }

        list.getChildAt(1)?.requestFocus()
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
        val row = Row(label)
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
        val row = Row(label)
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
        val row = Row(label, arrows = false)
        row.value.text = hint
        row.value.setTextColor(0x99FFFFFF.toInt())
        row.onStep = { onClick() }
        list.addView(row.view)
    }

    /** Una fila enfocable: etiqueta a la izquierda y valor a la derecha entre ‹ ›; ◀ ▶ y OK cambian el valor. */
    private inner class Row(label: String, private val arrows: Boolean = true) {
        var onStep: (Int) -> Unit = {}
        val value = TextView(this@CustomizeActivity).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        private val arrowL = arrow("‹")
        private val arrowR = arrow("›")

        val view = LinearLayout(this@CustomizeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(TextView(this@CustomizeActivity).apply {
                text = label
                textSize = 19f
                setTextColor(0xE6FFFFFF.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (arrows) addView(arrowL)
            addView(value, LinearLayout.LayoutParams(dp(250f), -2))
            if (arrows) addView(arrowR)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setOnFocusChangeListener { _, focused ->
                background = if (focused) GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(0x26FFFFFF)
                    setStroke(dp(2f), Theme.accent(context))
                } else null
                arrowL.alpha = if (focused) 1f else 0.25f
                arrowR.alpha = if (focused) 1f else 0.25f
            }
            setOnKeyListener { _, keyCode, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (arrows) { onStep(-1); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (arrows) { onStep(+1); true } else false
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (ev.repeatCount == 0) onStep(+1)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun arrow(s: String) = TextView(this@CustomizeActivity).apply {
            text = s
            textSize = 26f
            setTextColor(Color.WHITE)
            alpha = 0.25f
            gravity = Gravity.CENTER
        }.also { it.minWidth = dp(24f) }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
