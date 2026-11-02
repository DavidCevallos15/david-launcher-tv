package dev.davidcevallos.tvremote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.davidcevallos.tvremote.core.Keys
import dev.davidcevallos.tvremote.core.Remote
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Clickpad estilo Siri Remote:
 *  - Deslizar → flechas (una por cada [STEP_DP] recorridos, así un swipe largo avanza varias filas)
 *  - Tocar el centro → OK · tocar un borde → flecha en esa dirección
 *  - Mantener presionado → OK largo (menús contextuales del TV)
 */
class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKey: ((code: Int, direction: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val step = STEP_DP * density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C22.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val touch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val rect = RectF()
    private val path = Path()

    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var longPressed = false
    private var touching = false

    private val longPress = Runnable {
        if (!moved) { longPressed = true; fire(Keys.DPAD_CENTER, Remote.START_LONG) }
    }

    override fun onDraw(canvas: Canvas) {
        val r = 28 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        val cx = width / 2f; val cy = height / 2f
        canvas.drawCircle(cx, cy, centerRadius(), ring)

        // Chevrons en los 4 bordes
        val e = 26 * density; val c = 7 * density
        drawChevron(canvas, cx, e, 0f, -1f, c)
        drawChevron(canvas, cx, height - e, 0f, 1f, c)
        drawChevron(canvas, e, cy, -1f, 0f, c)
        drawChevron(canvas, width - e, cy, 1f, 0f, c)

        if (touching) canvas.drawCircle(lastX, lastY, 34 * density, touch)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, s: Float) {
        path.reset()
        if (dx == 0f) { path.moveTo(x - s, y - dy * s / 2); path.lineTo(x, y + dy * s / 2); path.lineTo(x + s, y - dy * s / 2) }
        else { path.moveTo(x - dx * s / 2, y - s); path.lineTo(x + dx * s / 2, y); path.lineTo(x - dx * s / 2, y + s) }
        canvas.drawPath(path, chevron)
    }

    private fun centerRadius() = min(width, height) * 0.22f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; longPressed = false; touching = true
                postDelayed(longPress, LONG_PRESS_MS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (!longPressed && (abs(dx) >= step || abs(dy) >= step)) {
                    moved = true
                    removeCallbacks(longPress)
                    fire(
                        if (abs(dx) > abs(dy)) (if (dx > 0) Keys.DPAD_RIGHT else Keys.DPAD_LEFT)
                        else (if (dy > 0) Keys.DPAD_DOWN else Keys.DPAD_UP),
                        Remote.SHORT,
                    )
                    lastX = e.x; lastY = e.y
                } else if (hypot(e.x - downX, e.y - downY) > step / 3) {
                    removeCallbacks(longPress) // ya es un gesto, no un long-press
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                touching = false
                when {
                    longPressed -> fire(Keys.DPAD_CENTER, Remote.END_LONG)
                    !moved -> tap(e.x, e.y)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (longPressed) fire(Keys.DPAD_CENTER, Remote.END_LONG)
                touching = false
                invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val dx = x - width / 2f; val dy = y - height / 2f
        if (hypot(dx, dy) <= centerRadius()) { fire(Keys.DPAD_CENTER, Remote.SHORT); return }
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val key = when {
            deg in -45.0..45.0 -> Keys.DPAD_RIGHT
            deg in 45.0..135.0 -> Keys.DPAD_DOWN
            deg in -135.0..-45.0 -> Keys.DPAD_UP
            else -> Keys.DPAD_LEFT
        }
        fire(key, Remote.SHORT)
    }

    private fun fire(code: Int, direction: Int) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onKey?.invoke(code, direction)
    }

    companion object {
        private const val STEP_DP = 44f
        private const val LONG_PRESS_MS = 550L
    }
}
