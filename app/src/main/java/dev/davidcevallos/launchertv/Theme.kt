package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** Opción elegible en Personalizar: id estable (se guarda), etiqueta visible. */
open class Option(val id: String, val label: String)

class ColorOption(id: String, label: String, val color: Int) : Option(id, label)

class FontOption(id: String, label: String, private val load: (Context) -> Typeface) : Option(id, label) {
    fun typeface(context: Context): Typeface = cache.getOrPut(id) { runCatching { load(context) }.getOrDefault(Typeface.DEFAULT) }

    private companion object {
        val cache = HashMap<String, Typeface>()
    }
}

class ClockSize(id: String, label: String, val sp: Float) : Option(id, label)

/** Catálogos de lo que se puede personalizar. Añadir una opción aquí la hace aparecer en Personalizar. */
object Theme {

    private fun system(family: String, style: Int = Typeface.NORMAL): (Context) -> Typeface =
        { Typeface.create(family, style) }

    private fun asset(path: String): (Context) -> Typeface =
        { Typeface.createFromAsset(it.assets, path) }

    val FONTS = listOf(
        FontOption("roboto-thin", "Delgada", system("sans-serif-thin")),
        FontOption("roboto-light", "Ligera", system("sans-serif-light")),
        FontOption("roboto", "Normal", system("sans-serif")),
        FontOption("roboto-medium", "Media", system("sans-serif-medium")),
        FontOption("roboto-condensed", "Condensada", system("sans-serif-condensed")),
        FontOption("roboto-black", "Negrita", system("sans-serif-black")),
        FontOption("serif", "Serif", system("serif")),
        FontOption("mono", "Monoespaciada", system("monospace")),
        FontOption("poppins", "Poppins", asset("fonts/poppins_light.ttf")),
        FontOption("bebas", "Bebas Neue", asset("fonts/bebas_neue.ttf")),
        FontOption("orbitron", "Orbitron", asset("fonts/orbitron.ttf")),
        FontOption("righteous", "Righteous", asset("fonts/righteous.ttf")),
    )

    val CLOCK_SIZES = listOf(
        ClockSize("s", "Pequeño", 44f),
        ClockSize("m", "Mediano", 56f),
        ClockSize("l", "Grande", 76f),
        ClockSize("xl", "Enorme", 100f),
    )

    val COLORS = listOf(
        ColorOption("white", "Blanco", 0xFFFFFFFF.toInt()),
        ColorOption("blue", "Azul", 0xFF4DA3FF.toInt()),
        ColorOption("cyan", "Cian", 0xFF22D3EE.toInt()),
        ColorOption("green", "Verde", 0xFF34D399.toInt()),
        ColorOption("amber", "Ámbar", 0xFFFBBF24.toInt()),
        ColorOption("orange", "Naranja", 0xFFFB923C.toInt()),
        ColorOption("pink", "Rosa", 0xFFF472B6.toInt()),
        ColorOption("violet", "Violeta", 0xFFA78BFA.toInt()),
        ColorOption("red", "Rojo", 0xFFF87171.toInt()),
    )

    val HOUR_FORMATS = listOf(Option("auto", "Según el TV"), Option("24", "24 horas"), Option("12", "12 horas"))

    val AUTO_HIDE = listOf(
        Option("0", "Nunca"), Option("10", "10 segundos"), Option("30", "30 segundos"), Option("60", "1 minuto"),
    )

    /** Fondo de las tarjetas de widgets: un solo color translúcido (barato de dibujar encima del video). */
    class GlassStyle(id: String, label: String, val fill: Int, val tint: Boolean = false) : Option(id, label)

    val GLASS = listOf(
        GlassStyle("light", "Cristal claro", 0x2EFFFFFF),
        GlassStyle("medium", "Cristal medio", 0x4DFFFFFF),
        GlassStyle("dark", "Cristal oscuro", 0x99000000.toInt()),
        GlassStyle("tint", "Color de acento", 0x59FFFFFF, tint = true),
    )

    fun font(id: String): FontOption = FONTS.firstOrNull { it.id == id } ?: FONTS[1]
    fun color(id: String): ColorOption = COLORS.firstOrNull { it.id == id } ?: COLORS[0]
    fun clockSize(id: String): ClockSize = CLOCK_SIZES.firstOrNull { it.id == id } ?: CLOCK_SIZES[1]
    fun glassStyle(id: String): GlassStyle = GLASS.firstOrNull { it.id == id } ?: GLASS[0]

    fun accent(context: Context): Int = color(LauncherPrefs(context).accent).color

    /** Fondo redondeado y plano para tarjetas de widgets y avisos. */
    fun glassDrawable(context: Context, radiusDp: Float = 24f): GradientDrawable {
        val prefs = LauncherPrefs(context)
        val style = glassStyle(prefs.glass)
        val accent = color(prefs.accent).color
        return GradientDrawable().apply {
            setColor(if (style.tint) (accent and 0x00FFFFFF) or (0x66 shl 24) else style.fill)
            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics)
        }
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
