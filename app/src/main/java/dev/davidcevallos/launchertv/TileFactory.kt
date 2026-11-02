package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
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

/**
 * Construye las tarjetas del dock / grids. Estilo tvOS: banner redondeado, escala, sombra y
 * aro blanco al enfocar. El orden de dibujo se resuelve en la raíz de cada tarjeta
 * (translationZ), así la enfocada siempre queda por encima de sus vecinas y nada se superpone.
 */
class TileFactory(private val context: Context) {

    class Tile(val root: View, private val image: ImageView, private val badge: View) {
        fun setImage(d: Drawable?) = image.setImageDrawable(d)
        fun setBadge(on: Boolean) {
            badge.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun appTile(
        entry: AppEntry,
        widthDp: Float,
        badge: Boolean = false,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): Tile = build(entry.label, entry.banner, entry.icon, widthDp, badge, onClick, onLongClick)

    fun actionTile(label: String, icon: Drawable?, widthDp: Float, onClick: () -> Unit): Tile =
        build(label, null, icon, widthDp, false, onClick, null)

    /** Tarjeta sin imagen todavía (miniaturas que se cargan en segundo plano): usar [Tile.setImage]. */
    fun mediaTile(label: String, widthDp: Float, badge: Boolean, onClick: () -> Unit): Tile =
        build(label, null, null, widthDp, badge, onClick, null)

    private fun build(
        label: String,
        banner: Drawable?,
        icon: Drawable?,
        widthDp: Float,
        badge: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): Tile {
        val w = dp(widthDp)
        val h = (w * 9f / 16f).toInt()
        val radius = dp(14f).toFloat()
        val iconOnly = banner == null && icon != null

        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(if (iconOnly) 70 else 40, 255, 255, 255))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
        }

        val image = ImageView(context)
        when {
            banner != null -> {
                image.setImageDrawable(banner)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                card.addView(image, FrameLayout.LayoutParams(w, h))
            }
            icon != null -> {
                image.setImageDrawable(icon)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                val s = (h * 0.55f).toInt()
                card.addView(image, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
            }
            else -> {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                card.addView(image, FrameLayout.LayoutParams(w, h))
            }
        }

        // Aro de foco: dibujado encima de la imagen, invisible hasta que la tarjeta recibe foco.
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
                setStroke(dp(3f), Color.WHITE)
            }
            alpha = 0f
        }
        card.addView(ring, FrameLayout.LayoutParams(-1, -1))

        val check = CheckBadge(context).apply { visibility = if (badge) View.VISIBLE else View.GONE }
        card.addView(check, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8f)
            marginEnd = dp(8f)
        })

        // Apps sin banner (solo ícono) no se reconocen sin nombre: la etiqueta queda siempre visible.
        val restAlpha = if (iconOnly) 0.7f else 0f
        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = restAlpha
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
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
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

        val interp = DecelerateInterpolator(2f)
        root.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.1f else 1f
            card.animate().scaleX(scale).scaleY(scale)
                .translationZ(if (focused) dp(18f).toFloat() else 0f)
                .setInterpolator(interp).setDuration(180).start()
            ring.animate().alpha(if (focused) 1f else 0f).setDuration(150).start()
            caption.animate().alpha(if (focused) 1f else restAlpha).setDuration(180).start()
            // La escala no cambia el layout: sin esto, la vecina de la derecha se dibuja encima de la enfocada.
            root.translationZ = if (focused) 1f else 0f
        }
        return Tile(root, image, check)
    }

    /** Círculo verde con palomita: "está en inicio" / "fondo activo". */
    private class CheckBadge(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawCircle(w / 2f, h / 2f, w / 2f, fill)
            stroke.strokeWidth = w * 0.11f
            path.reset()
            path.moveTo(w * 0.28f, h * 0.52f)
            path.lineTo(w * 0.44f, h * 0.68f)
            path.lineTo(w * 0.74f, h * 0.34f)
            canvas.drawPath(path, stroke)
        }
    }
}
