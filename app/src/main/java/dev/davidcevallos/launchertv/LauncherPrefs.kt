package dev.davidcevallos.launchertv

import android.content.Context

/** Preferencias del usuario. Todo lo editable desde el TV vive aquí; los valores son ids estables (no índices). */
class LauncherPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    // ---------- Fondo y dock ----------

    /** [WallpaperStore.AUTO] = rotación diaria; si no, el id de un fondo concreto. */
    var wallpaperId: String
        get() = str(KEY_WALLPAPER, WallpaperStore.AUTO)
        set(value) = put(KEY_WALLPAPER, value)

    /** Dock personalizado, o null si el usuario aún no lo ha tocado (se usa la lista por defecto). */
    var dockPackages: List<String>?
        get() = sp.getString(KEY_DOCK, null)?.split(',')?.filter { it.isNotBlank() }
        set(value) {
            val e = sp.edit()
            if (value == null) e.remove(KEY_DOCK) else e.putString(KEY_DOCK, value.joinToString(","))
            e.apply()
        }

    /** Marca "empieza a organizar apps al volver a Home" (la pone Personalizar, la consume Home). */
    var pendingMoveMode: Boolean
        get() = sp.getBoolean(KEY_PENDING_MOVE, false)
        set(value) = put(KEY_PENDING_MOVE, value)

    // ---------- Reloj y fecha ----------

    var clockFont: String
        get() = str(KEY_CLOCK_FONT, "roboto-light")
        set(value) = put(KEY_CLOCK_FONT, value)

    var clockSize: String
        get() = str(KEY_CLOCK_SIZE, "m")
        set(value) = put(KEY_CLOCK_SIZE, value)

    var dateFont: String
        get() = str(KEY_DATE_FONT, "roboto")
        set(value) = put(KEY_DATE_FONT, value)

    /** "auto" (según el TV), "24" o "12". */
    var hourFormat: String
        get() = str(KEY_HOUR_FORMAT, "auto")
        set(value) = put(KEY_HOUR_FORMAT, value)

    // ---------- Widgets ----------

    var showClock: Boolean
        get() = sp.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = put(KEY_SHOW_CLOCK, value)

    var showDate: Boolean
        get() = sp.getBoolean(KEY_SHOW_DATE, true)
        set(value) = put(KEY_SHOW_DATE, value)

    var showGreeting: Boolean
        get() = sp.getBoolean(KEY_SHOW_GREETING, true)
        set(value) = put(KEY_SHOW_GREETING, value)

    var showWeather: Boolean
        get() = sp.getBoolean(KEY_SHOW_WEATHER, true)
        set(value) = put(KEY_SHOW_WEATHER, value)

    /** "auto" (por IP) o el id de una ciudad de [WeatherRepository.CITIES]. */
    var weatherCity: String
        get() = str(KEY_WEATHER_CITY, "auto")
        set(value) = put(KEY_WEATHER_CITY, value)

    var fahrenheit: Boolean
        get() = sp.getBoolean(KEY_FAHRENHEIT, false)
        set(value) = put(KEY_FAHRENHEIT, value)

    /** Último clima descargado (JSON propio) para pintar al instante al abrir Home. */
    var weatherCache: String?
        get() = sp.getString(KEY_WEATHER_CACHE, null)
        set(value) = if (value == null) sp.edit().remove(KEY_WEATHER_CACHE).apply() else put(KEY_WEATHER_CACHE, value)

    /** Coordenadas de la última geolocalización por IP: "lat,lon,ciudad,timestamp". */
    var geoCache: String?
        get() = sp.getString(KEY_GEO_CACHE, null)
        set(value) = if (value == null) sp.edit().remove(KEY_GEO_CACHE).apply() else put(KEY_GEO_CACHE, value)

    // ---------- Colores ----------

    var accent: String
        get() = str(KEY_ACCENT, "white")
        set(value) = put(KEY_ACCENT, value)

    var clockColor: String
        get() = str(KEY_CLOCK_COLOR, "white")
        set(value) = put(KEY_CLOCK_COLOR, value)

    var glass: String
        get() = str(KEY_GLASS, "light")
        set(value) = put(KEY_GLASS, value)

    // ---------- Comportamiento ----------

    var welcome: Boolean
        get() = sp.getBoolean(KEY_WELCOME, true)
        set(value) = put(KEY_WELCOME, value)

    /** Cuándo se saludó por última vez y en qué arranque del TV (para no repetirlo si el sistema recicla el proceso). */
    var lastGreetAt: Long
        get() = sp.getLong(KEY_GREET_AT, 0L)
        set(value) = sp.edit().putLong(KEY_GREET_AT, value).apply()

    var lastGreetBoot: Long
        get() = sp.getLong(KEY_GREET_BOOT, 0L)
        set(value) = sp.edit().putLong(KEY_GREET_BOOT, value).apply()

    /** La pantalla se apagó (TV en espera) desde el último saludo. */
    var screenOffSinceGreet: Boolean
        get() = sp.getBoolean(KEY_SCREEN_OFF, false)
        set(value) = put(KEY_SCREEN_OFF, value)

    /** Segundos sin tocar el control para ocultar el menú solo; 0 = nunca. */
    var autoHideSeconds: Int
        get() = sp.getInt(KEY_AUTO_HIDE, 0)
        set(value) = sp.edit().putInt(KEY_AUTO_HIDE, value).apply()

    /** Borra todo lo estético (no toca fondo, dock ni ciudad). */
    fun resetStyle() {
        sp.edit()
            .remove(KEY_CLOCK_FONT).remove(KEY_CLOCK_SIZE).remove(KEY_DATE_FONT).remove(KEY_HOUR_FORMAT)
            .remove(KEY_SHOW_CLOCK).remove(KEY_SHOW_DATE).remove(KEY_SHOW_GREETING).remove(KEY_SHOW_WEATHER)
            .remove(KEY_ACCENT).remove(KEY_CLOCK_COLOR).remove(KEY_GLASS)
            .apply()
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    private companion object {
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_DOCK = "dock"
        const val KEY_PENDING_MOVE = "pending_move"
        const val KEY_CLOCK_FONT = "clock_font"
        const val KEY_CLOCK_SIZE = "clock_size"
        const val KEY_DATE_FONT = "date_font"
        const val KEY_HOUR_FORMAT = "hour_format"
        const val KEY_SHOW_CLOCK = "show_clock"
        const val KEY_SHOW_DATE = "show_date"
        const val KEY_SHOW_GREETING = "show_greeting"
        const val KEY_SHOW_WEATHER = "show_weather"
        const val KEY_WEATHER_CITY = "weather_city"
        const val KEY_FAHRENHEIT = "fahrenheit"
        const val KEY_WEATHER_CACHE = "weather_cache"
        const val KEY_GEO_CACHE = "geo_cache"
        const val KEY_ACCENT = "accent"
        const val KEY_CLOCK_COLOR = "clock_color"
        const val KEY_GLASS = "glass"
        const val KEY_WELCOME = "welcome"
        const val KEY_AUTO_HIDE = "auto_hide"
        const val KEY_GREET_AT = "greet_at"
        const val KEY_GREET_BOOT = "greet_boot"
        const val KEY_SCREEN_OFF = "screen_off"
    }
}
