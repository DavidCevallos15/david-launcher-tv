package dev.davidcevallos.launchertv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tarjeta "Reproduciendo" (carátula, título, artista, app de origen).
 *  - [big] = true: la del modo "solo wallpaper", con botones enfocables: anterior, pausa/play, siguiente y cerrar.
 *  - [big] = false: la pastilla pequeña que acompaña al reloj cuando el menú está abierto (solo informa).
 */
class NowPlayingCard(private val context: Context, private val big: Boolean) {

    private val pm = context.packageManager
    private val artSize = context.dpi(if (big) 92f else 40f)
    private val accent get() = Theme.accent(context)

    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0x33FFFFFF)
        val r = context.dpi(if (big) 14f else 8f).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        clipToOutline = true
    }
    private val appIcon = ImageView(context)
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = if (big) 21f else 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    }
    private val artist = TextView(context).apply {
        setTextColor(0xE6FFFFFF.toInt())
        textSize = if (big) 16f else 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val playPause = button(R.drawable.ic_pause, "Pausa / reproducir") { MediaHub.playPause() }
    private val prev = button(R.drawable.ic_skip_prev, "Anterior") { MediaHub.previous() }
    private val next = button(R.drawable.ic_skip_next, "Siguiente") { MediaHub.next() }
    private val close = button(R.drawable.ic_close, "Cerrar la app") { MediaHub.close() }

    /** El botón que recibe el foco al entrar al modo "solo wallpaper". */
    val primaryFocus: View get() = playPause

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        val h = context.dpi(if (big) 16f else 8f)
        setPadding(h, context.dpi(if (big) 14f else 6f), h + context.dpi(if (big) 8f else 6f), context.dpi(if (big) 14f else 6f))
        visibility = View.GONE
        addView(art, LinearLayout.LayoutParams(artSize, artSize))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        texts.addView(title, LinearLayout.LayoutParams(-2, -2))
        val who = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        who.addView(appIcon, LinearLayout.LayoutParams(context.dpi(if (big) 18f else 14f), context.dpi(if (big) 18f else 14f)).apply {
            marginEnd = context.dpi(6f)
        })
        who.addView(artist, LinearLayout.LayoutParams(-2, -2))
        texts.addView(who, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dpi(2f) })
        if (big) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
            val gap = context.dpi(10f)
            listOf(prev, playPause, next, close).forEach { b ->
                row.addView(b, LinearLayout.LayoutParams(context.dpi(46f), context.dpi(46f)).apply { marginEnd = gap })
            }
            texts.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dpi(10f) })
        }
        addView(texts, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dpi(if (big) 16f else 10f) })
        title.maxWidth = context.dpi(if (big) 300f else 230f)
        artist.maxWidth = context.dpi(if (big) 280f else 200f)
    }

    private var shownPkg: String? = null

    /** Repinta con lo que suena ahora (o se oculta si no hay nada). */
    fun bind(track: Track?) {
        if (track == null || !LauncherPrefs(context).showPlayer) {
            view.visibility = View.GONE
            shownPkg = null
            return
        }
        view.background = Theme.glassDrawable(context, if (big) 24f else 20f)
        view.visibility = View.VISIBLE
        title.text = track.title
        artist.text = track.artist
        if (track.art != null) art.setImageBitmap(track.art) else art.setImageDrawable(null)
        if (shownPkg != track.pkg) {
            shownPkg = track.pkg
            appIcon.setImageDrawable(runCatching { pm.getApplicationIcon(track.pkg) }.getOrNull())
        }
        playPause.setImageResource(if (track.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    private fun button(icon: Int, description: String, onClick: () -> Unit): ImageView {
        val pad = context.dpi(11f)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33FFFFFF)
            setStroke(context.dpi(3f), Color.TRANSPARENT)
        }
        return ImageView(context).apply {
            setImageResource(icon)
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            background = bg
            isFocusable = big
            isFocusableInTouchMode = big
            isClickable = big
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused ->
                bg.setStroke(context.dpi(3f), if (focused) accent else Color.TRANSPARENT)
                v.animate().scaleX(if (focused) 1.18f else 1f).scaleY(if (focused) 1.18f else 1f).setDuration(120).start()
            }
        }
    }
}
