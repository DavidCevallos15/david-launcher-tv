package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Construye las tarjetas del dock / grid. Estilo tvOS: banner redondeado, escala + sombra al enfocar. */
class TileFactory(private val context: Context) {

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun appTile(entry: AppEntry, widthDp: Float = 240f, onClick: () -> Unit): View =
        tile(entry.label, entry.banner, entry.icon, widthDp, onClick)

    fun actionTile(label: String, icon: Drawable?, widthDp: Float = 240f, onClick: () -> Unit): View =
        tile(label, null, icon, widthDp, onClick)

    private fun tile(
        label: String,
        banner: Drawable?,
        icon: Drawable?,
        widthDp: Float,
        onClick: () -> Unit,
    ): View {
        val w = dp(widthDp)
        val h = (w * 9f / 16f).toInt()
        val radius = dp(14f).toFloat()

        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(90, 255, 255, 255))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
        }

        if (banner != null) {
            card.addView(ImageView(context).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(w, h))
        } else {
            val s = (h * 0.45f).toInt()
            card.addView(ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }

        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            alpha = 0f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
            maxLines = 1
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = label
            addView(card, LinearLayout.LayoutParams(w, h))
            addView(caption, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14f)
            })
            setOnClickListener { onClick() }
        }

        val interp = DecelerateInterpolator(2f)
        root.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.12f else 1f
            card.animate().scaleX(scale).scaleY(scale)
                .translationZ(if (focused) dp(18f).toFloat() else 0f)
                .setInterpolator(interp).setDuration(180).start()
            caption.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
        }
        return root
    }
}
