package dev.davidcevallos.launchertv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/** Una fila enfocable de ajustes: etiqueta a la izquierda y valor a la derecha entre ‹ ›; ◀ ▶ y OK cambian el valor. */
class SettingRow(private val activity: BaseActivity, label: String, private val arrows: Boolean = true) {
    var onStep: (Int) -> Unit = {}
    val value = TextView(activity).apply {
        textSize = 19f
        setTextColor(Color.WHITE)
        gravity = Gravity.END
    }
    private val arrowL = arrow("‹")
    private val arrowR = arrow("›")

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dpi(14f), activity.dpi(7f), activity.dpi(14f), activity.dpi(7f))
        isFocusable = true
        isFocusableInTouchMode = true
        addView(TextView(activity).apply {
            text = label
            textSize = 19f
            setTextColor(0xE6FFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (arrows) addView(arrowL)
        addView(value, LinearLayout.LayoutParams(activity.dpi(250f), -2))
        if (arrows) addView(arrowR)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnFocusChangeListener { _, focused ->
            background = if (focused) GradientDrawable().apply {
                cornerRadius = activity.dpi(14f).toFloat()
                setColor(0x26FFFFFF)
                setStroke(activity.dpi(2f), Theme.accent(activity))
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

    private fun arrow(s: String) = TextView(activity).apply {
        text = s
        textSize = 26f
        setTextColor(Color.WHITE)
        alpha = 0.25f
        gravity = Gravity.CENTER
        minWidth = activity.dpi(24f)
    }
}
