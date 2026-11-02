package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Ícono del clima dibujado en código (sin imágenes ni emojis, que muchas TVs no traen): nítido a cualquier tamaño. */
class WeatherIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Kind { CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, PARTLY_NIGHT, CLOUDY, FOG, RAIN, SNOW, STORM }

    var kind: Kind = Kind.CLEAR_DAY
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f
    }
    private val path = Path()
    private val cut = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 100f // todo se dibuja en una cuadrícula de 100 x 100
        canvas.save()
        canvas.translate((width - 100 * s) / 2f, (height - 100 * s) / 2f)
        canvas.scale(s, s)
        when (kind) {
            Kind.CLEAR_DAY -> sun(canvas, 50f, 50f, 20f)
            Kind.CLEAR_NIGHT -> moon(canvas, 50f, 50f, 26f)
            Kind.PARTLY_DAY -> { sun(canvas, 36f, 36f, 15f); cloud(canvas, 58f, 62f, 0.85f, CLOUD) }
            Kind.PARTLY_NIGHT -> { moon(canvas, 36f, 36f, 20f); cloud(canvas, 58f, 62f, 0.85f, CLOUD) }
            Kind.CLOUDY -> { cloud(canvas, 38f, 40f, 0.7f, DARK_CLOUD); cloud(canvas, 56f, 60f, 0.95f, CLOUD) }
            Kind.FOG -> { cloud(canvas, 50f, 38f, 0.85f, DARK_CLOUD); fog(canvas) }
            Kind.RAIN -> { cloud(canvas, 50f, 40f, 0.95f, DARK_CLOUD); rain(canvas) }
            Kind.SNOW -> { cloud(canvas, 50f, 40f, 0.95f, CLOUD); snow(canvas) }
            Kind.STORM -> { cloud(canvas, 50f, 38f, 0.95f, DARK_CLOUD); bolt(canvas) }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = SUN
        canvas.drawCircle(cx, cy, r, fill)
        line.color = SUN
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            canvas.drawLine(cx + dx * (r + 6f), cy + dy * (r + 6f), cx + dx * (r + 13f), cy + dy * (r + 13f), line)
        }
    }

    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        cut.reset()
        cut.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW)
        path.op(cut, Path.Op.DIFFERENCE)
        fill.color = MOON
        canvas.drawPath(path, fill)
    }

    private fun cloud(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        fill.color = color
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.drawCircle(-15f, 6f, 13f, fill)
        canvas.drawCircle(2f, -5f, 18f, fill)
        canvas.drawCircle(18f, 5f, 13f, fill)
        rect.set(-28f, 4f, 31f, 19f)
        canvas.drawRoundRect(rect, 7f, 7f, fill)
        canvas.restore()
    }

    private fun rain(canvas: Canvas) {
        line.color = RAIN
        for (x in floatArrayOf(36f, 50f, 64f)) canvas.drawLine(x, 68f, x - 5f, 82f, line)
    }

    private fun snow(canvas: Canvas) {
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(36f, 74f, 4.5f, fill)
        canvas.drawCircle(50f, 82f, 4.5f, fill)
        canvas.drawCircle(64f, 74f, 4.5f, fill)
    }

    private fun fog(canvas: Canvas) {
        line.color = DARK_CLOUD
        canvas.drawLine(26f, 66f, 74f, 66f, line)
        canvas.drawLine(34f, 77f, 82f, 77f, line)
        canvas.drawLine(26f, 88f, 66f, 88f, line)
    }

    private fun bolt(canvas: Canvas) {
        path.reset()
        path.moveTo(54f, 60f)
        path.lineTo(40f, 80f)
        path.lineTo(50f, 80f)
        path.lineTo(44f, 96f)
        path.lineTo(64f, 72f)
        path.lineTo(53f, 72f)
        path.close()
        fill.color = BOLT
        canvas.drawPath(path, fill)
    }

    private companion object {
        const val SUN = 0xFFFFC83D.toInt()
        const val MOON = 0xFFFDE9A8.toInt()
        const val CLOUD = 0xFFF1F5F9.toInt()
        const val DARK_CLOUD = 0xFF94A3B8.toInt()
        const val RAIN = 0xFF60A5FA.toInt()
        const val BOLT = 0xFFFACC15.toInt()
    }
}
