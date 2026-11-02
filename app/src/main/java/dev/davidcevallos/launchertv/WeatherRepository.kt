package dev.davidcevallos.launchertv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

class Weather(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val isDay: Boolean,
    val humidity: Int,
    val windKmh: Int,
    val max: Int,
    val min: Int,
    val city: String,
    val fahrenheit: Boolean,
    val fetchedAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("t", temp).put("f", feels).put("c", code).put("d", isDay).put("h", humidity).put("w", windKmh)
        .put("x", max).put("n", min).put("city", city).put("F", fahrenheit).put("at", fetchedAt).toString()

    companion object {
        fun fromJson(s: String): Weather? = runCatching {
            val o = JSONObject(s)
            Weather(
                o.getInt("t"), o.getInt("f"), o.getInt("c"), o.getBoolean("d"), o.getInt("h"), o.getInt("w"),
                o.getInt("x"), o.getInt("n"), o.getString("city"), o.getBoolean("F"), o.getLong("at"),
            )
        }.getOrNull()
    }
}

class City(val id: String, val name: String, val lat: Double, val lon: Double)

/**
 * Clima actual con Open-Meteo (gratis, sin clave ni cuenta). Sin librerías: HttpURLConnection + org.json.
 * La ciudad es fija (lista) o automática por IP (ipwho.is, con geojs.io de respaldo).
 * Llamar [fetch] siempre fuera del hilo principal.
 */
class WeatherRepository(context: Context) {

    private val prefs = LauncherPrefs(context)

    /** Último clima guardado, o null si no hay o si ya no corresponde a la ciudad / unidad elegidas. */
    fun cached(): Weather? {
        val w = prefs.weatherCache?.let { Weather.fromJson(it) } ?: return null
        return if (w.fahrenheit == prefs.fahrenheit) w else null
    }

    fun isFresh(w: Weather?): Boolean = w != null && System.currentTimeMillis() - w.fetchedAt < FRESH_MS

    fun fetch(): Weather? = runCatching {
        val (lat, lon, name) = locate() ?: return null
        val unit = if (prefs.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1$unit"
        val json = JSONObject(httpGet(url))
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        Weather(
            temp = cur.getDouble("temperature_2m").roundToInt(),
            feels = cur.getDouble("apparent_temperature").roundToInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.getInt("is_day") == 1,
            humidity = cur.getInt("relative_humidity_2m"),
            windKmh = cur.getDouble("wind_speed_10m").roundToInt(),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            city = name,
            fahrenheit = prefs.fahrenheit,
            fetchedAt = System.currentTimeMillis(),
        ).also { prefs.weatherCache = it.toJson() }
    }.getOrNull()

    private data class Place(val lat: Double, val lon: Double, val name: String)

    private fun locate(): Place? {
        val chosen = CITIES.firstOrNull { it.id == prefs.weatherCity }
        if (chosen != null) return Place(chosen.lat, chosen.lon, chosen.name)

        // Automática: la geolocalización por IP casi no cambia, se reutiliza 12 h.
        prefs.geoCache?.split('|')?.takeIf { it.size == 4 }?.let { p ->
            val at = p[3].toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < GEO_TTL_MS) {
                return Place(p[0].toDouble(), p[1].toDouble(), p[2])
            }
        }
        val place = runCatching {
            val j = JSONObject(httpGet("https://ipwho.is/"))
            if (!j.optBoolean("success", false)) error("ipwho")
            Place(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city", ""))
        }.recoverCatching {
            val j = JSONObject(httpGet("https://get.geojs.io/v1/ip/geo.json"))
            Place(j.getString("latitude").toDouble(), j.getString("longitude").toDouble(), j.optString("city", ""))
        }.getOrNull() ?: return CITIES.first().let { Place(it.lat, it.lon, it.name) }
        prefs.geoCache = "${place.lat}|${place.lon}|${place.name}|${System.currentTimeMillis()}"
        return place
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "DavidLauncherTV/1.0")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val FRESH_MS = 15 * 60_000L
        private const val GEO_TTL_MS = 12 * 3_600_000L

        val CITIES = listOf(
            City("quito", "Quito", -0.1807, -78.4678),
            City("guayaquil", "Guayaquil", -2.1894, -79.8891),
            City("cuenca", "Cuenca", -2.9001, -79.0059),
            City("manta", "Manta", -0.9677, -80.7089),
            City("portoviejo", "Portoviejo", -1.0546, -80.4545),
            City("chone", "Chone", -0.6833, -80.1000),
            City("ambato", "Ambato", -1.2417, -78.6197),
            City("loja", "Loja", -3.9931, -79.2042),
            City("esmeraldas", "Esmeraldas", 0.9592, -79.6539),
            City("machala", "Machala", -3.2581, -79.9554),
            City("santo-domingo", "Santo Domingo", -0.2530, -79.1754),
            City("riobamba", "Riobamba", -1.6636, -78.6546),
            City("ibarra", "Ibarra", 0.3517, -78.1223),
            City("salinas", "Salinas", -2.2146, -80.9553),
        )

        /** Códigos WMO de Open-Meteo → texto en español. */
        fun describe(code: Int): String = when (code) {
            0 -> "Despejado"
            1 -> "Mayormente despejado"
            2 -> "Parcialmente nublado"
            3 -> "Nublado"
            45, 48 -> "Niebla"
            51, 53, 55, 56, 57 -> "Llovizna"
            61, 63 -> "Lluvia"
            65, 66, 67 -> "Lluvia fuerte"
            71, 73, 75, 77, 85, 86 -> "Nieve"
            80, 81 -> "Chubascos"
            82 -> "Chubascos fuertes"
            95, 96, 99 -> "Tormenta"
            else -> "—"
        }

        fun iconKind(code: Int, isDay: Boolean): WeatherIconView.Kind = when (code) {
            0, 1 -> if (isDay) WeatherIconView.Kind.CLEAR_DAY else WeatherIconView.Kind.CLEAR_NIGHT
            2 -> if (isDay) WeatherIconView.Kind.PARTLY_DAY else WeatherIconView.Kind.PARTLY_NIGHT
            3 -> WeatherIconView.Kind.CLOUDY
            45, 48 -> WeatherIconView.Kind.FOG
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIconView.Kind.RAIN
            71, 73, 75, 77, 85, 86 -> WeatherIconView.Kind.SNOW
            95, 96, 99 -> WeatherIconView.Kind.STORM
            else -> WeatherIconView.Kind.CLOUDY
        }

        fun units(fahrenheit: Boolean) = if (fahrenheit) "°F" else "°C"

        fun format(w: Weather) = String.format(Locale("es"), "%d°", w.temp)
    }
}
