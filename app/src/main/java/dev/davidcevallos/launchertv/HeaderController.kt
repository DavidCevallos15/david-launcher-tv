package dev.davidcevallos.launchertv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Los widgets de la parte alta (reloj, fecha, saludo por hora, clima en tiempo real).
 * Lo comparten Home y Personalizar (que lo usa como vista previa en vivo).
 *
 * Ciclo de vida: [start] en onResume, [stop] en onPause, [release] en onDestroy.
 */
class HeaderController(
    private val context: Context,
    root: View,
    /** Multiplica el tamaño del reloj: la versión "solo wallpaper" lo muestra más grande. */
    private val scale: Float = 1f,
    /** false = la tarjeta del clima oculta humedad y viento (versión compacta). */
    private val details: Boolean = true,
    /** false = no descarga el clima: lo recibe de otro controlador vía [setWeather]. */
    private val fetchWeather: Boolean = true,
) {

    /** Avisa cuando llega clima nuevo, para compartirlo con otro controlador sin descargarlo dos veces. */
    var onWeather: ((Weather?) -> Unit)? = null


    private val prefs = LauncherPrefs(context)
    private val weatherRepo = WeatherRepository(context)
    private val main = Handler(Looper.getMainLooper())
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val locale = Locale("es", "EC")

    private val clock = root.findViewById<TextView>(R.id.clock)
    private val date = root.findViewById<TextView>(R.id.date)
    private val greeting = root.findViewById<TextView>(R.id.greetingLine)
    private val weatherCard = root.findViewById<LinearLayout>(R.id.weatherCard)
    private val weatherIcon = root.findViewById<WeatherIconView>(R.id.weatherIcon)
    private val weatherTemp = root.findViewById<TextView>(R.id.weatherTemp)
    private val weatherCond = root.findViewById<TextView>(R.id.weatherCond)
    private val weatherRange = root.findViewById<TextView>(R.id.weatherRange)
    private val weatherExtra = root.findViewById<TextView>(R.id.weatherExtra)

    private var timeFmt = SimpleDateFormat("HH:mm", locale)
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", locale)
    private var running = false
    private var weather: Weather? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTexts()
            main.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather(force = false)
            main.postDelayed(this, WeatherRepository.FRESH_MS)
        }
    }

    fun start() {
        running = true
        applyStyle()
        main.removeCallbacks(tick)
        main.post(tick)
        main.removeCallbacks(weatherTick)
        if (fetchWeather) main.post(weatherTick) else setWeather(weatherRepo.cached())
    }

    /** Recibe el clima de otro controlador. */
    fun setWeather(w: Weather?) {
        weather = w
        renderWeather()
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        main.removeCallbacks(weatherTick)
    }

    fun release() {
        stop()
        if (fetchWeather) io.shutdownNow()
    }

    /** Vuelve a leer las preferencias (fuentes, tamaños, colores, qué widgets se ven) y repinta. */
    fun applyStyle() {
        val size = Theme.clockSize(prefs.clockSize)
        val clockColor = Theme.color(prefs.clockColor).color

        clock.typeface = Theme.font(prefs.clockFont).typeface(context)
        clock.textSize = size.sp * scale
        clock.setTextColor(clockColor)
        clock.visibility = vis(prefs.showClock)

        val dateTypeface = Theme.font(prefs.dateFont).typeface(context)
        date.typeface = dateTypeface
        date.textSize = (size.sp * scale * 0.34f).coerceIn(17f, 34f)
        date.setTextColor(Theme.withAlpha(clockColor, 0.92f))
        date.visibility = vis(prefs.showDate)

        greeting.typeface = dateTypeface
        greeting.visibility = vis(prefs.showGreeting)

        weatherCard.background = Theme.glassDrawable(context, 22f)
        weatherCond.typeface = dateTypeface

        timeFmt = SimpleDateFormat(
            when (prefs.hourFormat) {
                "24" -> "HH:mm"
                "12" -> "h:mm a"
                else -> if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            },
            locale,
        )
        updateTexts()
        renderWeather()
    }

    private fun updateTexts() {
        val now = Date()
        clock.text = timeFmt.format(now)
        date.text = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
        greeting.text = greetingFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    /** Pide el clima (siempre fuera del hilo principal). Si lo guardado es reciente y no se fuerza, solo lo pinta. */
    fun refreshWeather(force: Boolean) {
        if (!prefs.showWeather) return
        val cached = weatherRepo.cached()
        if (weather == null && cached != null) {
            weather = cached
            renderWeather()
            onWeather?.invoke(cached)
        }
        if (!force && weatherRepo.isFresh(cached)) return
        try {
            io.execute {
                val fresh = weatherRepo.fetch() ?: return@execute
                main.post {
                    if (!running) return@post
                    weather = fresh
                    renderWeather()
                    onWeather?.invoke(fresh)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Se cerró la pantalla entre medias.
        }
    }

    /** Descarta lo mostrado y vuelve a pedir (ciudad o unidades cambiadas). */
    fun reloadWeather() {
        weather = null
        weatherCard.visibility = View.GONE
        onWeather?.invoke(null)
        refreshWeather(force = true)
    }

    private fun renderWeather() {
        val w = weather
        if (!prefs.showWeather || w == null || w.fahrenheit != prefs.fahrenheit) {
            weatherCard.visibility = View.GONE
            return
        }
        val u = WeatherRepository.units(w.fahrenheit)
        weatherIcon.kind = WeatherRepository.iconKind(w.code, w.isDay)
        weatherTemp.text = "${w.temp}°"
        weatherCond.text = WeatherRepository.describe(w.code)
        weatherRange.text = "Máx ${w.max}° · Mín ${w.min}° · Sensación ${w.feels}$u"
        weatherExtra.visibility = if (details) View.VISIBLE else View.GONE
        weatherExtra.text = buildString {
            append("Humedad ${w.humidity}% · Viento ${w.windKmh} km/h")
            if (w.city.isNotBlank()) append(" · ${w.city}")
        }
        weatherCard.visibility = View.VISIBLE
    }

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    companion object {
        /** Saludo según la hora del día. */
        fun greetingFor(hour: Int): String = when (hour) {
            in 5..11 -> "Buenos días, ${LauncherConfig.USER_NAME}"
            in 12..18 -> "Buenas tardes, ${LauncherConfig.USER_NAME}"
            in 19..23 -> "Buenas noches, ${LauncherConfig.USER_NAME}"
            else -> "¿Madrugando, eh, ${LauncherConfig.USER_NAME}?"
        }
    }
}
