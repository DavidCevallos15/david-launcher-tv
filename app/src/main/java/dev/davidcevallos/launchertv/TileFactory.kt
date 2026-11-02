package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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

    class Tile(
        val root: View,
        private val image: ImageView,
        private val badge: View,
        private val liveDot: View,
        private val radiusPx: Float,
        private val onMoving: (Boolean) -> Unit,
    ) {
        /** Punto de color en la esquina: la app está en uso en segundo plano (suena música, etc.). */
        fun setLive(on: Boolean) {
            liveDot.visibility = if (on) View.VISIBLE else View.GONE
        }

        fun setImage(d: Drawable?) {
            image.setImageDrawable(if (d is BitmapDrawable) RoundedCoverDrawable(d.bitmap, radiusPx) else d)
        }

        /** Modo "mover": la tarjeta queda levantada y resaltada aunque cambie de sitio. */
        fun setMoving(on: Boolean) = onMoving(on)

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
        live: Boolean = false,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): Tile = build(entry.label, entry.banner, entry.icon, widthDp, badge, live, onClick, onLongClick)

    fun actionTile(label: String, icon: Drawable?, widthDp: Float, onClick: () -> Unit): Tile =
        build(label, null, icon, widthDp, false, false, onClick, null)

    /**
     * Botón redondo con ícono y nombre debajo: para las acciones del sistema (Apps, Fondos, Ajustes).
     * Mucho más barato de dibujar que una tarjeta 16:9 y ocupa una fracción del alto.
     */
    fun roundButton(label: String, icon: Drawable?, sizeDp: Float, onClick: () -> Unit): Tile {
        val d = dp(sizeDp)
        val accent = Theme.accent(context)
        val disc = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(56, 255, 255, 255))
            }
        }
        val image = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val s = (d * 0.5f).toInt()
        disc.addView(image, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), accent)
            }
            alpha = 0f
        }
        disc.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            alpha = 0.7f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = label
            addView(disc, LinearLayout.LayoutParams(d, d))
            addView(caption, LinearLayout.LayoutParams(d + dp(44f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
            setOnClickListener { onClick() }
        }
        root.setOnFocusChangeListener { _, focused ->
            disc.animate().scaleX(if (focused) 1.14f else 1f).scaleY(if (focused) 1.14f else 1f)
                .setInterpolator(DecelerateInterpolator(2f)).setDuration(160).start()
            ring.animate().alpha(if (focused) 1f else 0f).setDuration(140).start()
            caption.animate().alpha(if (focused) 1f else 0.7f).setDuration(160).start()
            root.translationZ = if (focused) 1f else 0f
        }
        val none = View(context).apply { visibility = View.GONE }
        return Tile(root, image, none, none, 0f) { }
    }

    /** Tarjeta sin imagen todavía (miniaturas que se cargan en segundo plano): usar [Tile.setImage]. */
    fun mediaTile(label: String, widthDp: Float, badge: Boolean, onClick: () -> Unit): Tile =
        build(label, null, null, widthDp, badge, false, onClick, null)

    private fun build(
        label: String,
        banner: Drawable?,
        icon: Drawable?,
        widthDp: Float,
        badge: Boolean,
        live: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): Tile {
        val w = dp(widthDp)
        val h = (w * 9f / 16f).toInt()
        val radius = dp(16f).toFloat()
        val iconOnly = banner == null && icon != null
        val accent = Theme.accent(context)

        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(if (iconOnly) 70 else 40, 255, 255, 255))
            }
        }

        val image = ImageView(context)
        when {
            banner != null -> {
                image.setImageDrawable(RoundedCoverDrawable(toBitmap(banner, w, h), radius))
                image.scaleType = ImageView.ScaleType.FIT_XY
                card.addView(image, FrameLayout.LayoutParams(w, h))
            }
            icon != null -> {
                image.setImageDrawable(icon)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                val s = (h * 0.55f).toInt()
                card.addView(image, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
            }
            else -> {
                image.scaleType = ImageView.ScaleType.FIT_XY
                card.addView(image, FrameLayout.LayoutParams(w, h))
            }
        }

        // Aro de foco: dibujado encima de la imagen, invisible hasta que la tarjeta recibe foco.
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), accent)
            }
            alpha = 0f
        }
        card.addView(ring, FrameLayout.LayoutParams(-1, -1))

        val check = CheckBadge(context).apply { visibility = if (badge) View.VISIBLE else View.GONE }
        card.addView(check, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8f)
            marginEnd = dp(8f)
        })

        val liveDot = LiveDot(context, accent).apply { visibility = if (live) View.VISIBLE else View.GONE }
        card.addView(liveDot, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(9f)
            marginStart = dp(9f)
        })

        // Apps sin banner (solo ícono) no se reconocen sin nombre: la etiqueta queda siempre visible.
        val restAlpha = if (iconOnly) 0.7f else 0f
        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = restAlpha
            gravity = Gravity.CENTER
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
        var moving = false
        fun render(focused: Boolean) {
            val up = focused || moving
            val scale = if (moving) 1.16f else if (focused) 1.08f else 1f
            card.animate().scaleX(scale).scaleY(scale)
                .setInterpolator(interp).setDuration(180).start()
            ring.animate().alpha(if (up) 1f else 0f).setDuration(150).start()
            caption.animate().alpha(if (up) 1f else restAlpha).setDuration(180).start()
            // La escala no cambia el layout: sin esto, la vecina de la derecha se dibuja encima de la enfocada.
            root.translationZ = if (up) 1f else 0f
        }
        root.setOnFocusChangeListener { _, focused -> render(focused) }
        return Tile(root, image, check, liveDot, radius) { on ->
            moving = on
            render(root.hasFocus())
        }
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

    /** Un banner de PackageManager puede ser vector, capas, etc.: se rasteriza una vez al tamaño de la tarjeta. */
    private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap.width > 0) return d.bitmap
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    /** Punto de "en uso": círculo del color de acento con borde blanco para que se vea sobre cualquier banner. */
    private class LiveDot(context: Context, private val color: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LiveDot.color }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 2f, fill)
            canvas.drawCircle(r, r, r - 2f, ring)
        }
    }
}

/**
 * Imagen con esquinas redondeadas dibujada con un BitmapShader (cubre el área, recortando el sobrante).
 * Evita clipToOutline, que en la GPU de este TV es muy caro cuando la tarjeta se escala al enfocarse.
 */
internal class RoundedCoverDrawable(private val bmp: Bitmap, private val radius: Float) : Drawable() {
    private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@RoundedCoverDrawable.shader }
    private val matrix = Matrix()
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) {
        val s = maxOf(b.width() / bmp.width.toFloat(), b.height() / bmp.height.toFloat())
        matrix.setScale(s, s)
        matrix.postTranslate(b.left + (b.width() - bmp.width * s) / 2f, b.top + (b.height() - bmp.height * s) / 2f)
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    // -1: sin tamaño propio, así el ImageView la estira al tamaño de la tarjeta.
    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
