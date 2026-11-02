package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.util.TypedValue
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import kotlin.math.roundToInt

/** Ancho de pantalla en dp reales (respeta el zoom de la interfaz, que cambia la densidad). */
fun Context.screenWidthDp(): Float = resources.displayMetrics.let { it.widthPixels / it.density }

/**
 * Ancho de pantalla en dp *sin* zoom. El tamaño de las tarjetas se calcula con esto: si se usara el ancho con zoom,
 * el zoom las agrandaría y las achicaría a la vez (las dejaría del mismo tamaño en pantalla).
 */
fun Context.unzoomedWidthDp(): Float = screenWidthDp() * LauncherPrefs(this).uiZoom.coerceIn(60, 140) / 100f

fun Context.dpi(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/**
 * Base de todas las pantallas. Aplica el "zoom de la interfaz": en vez de tocar cada tamaño, se cambia
 * la densidad con la que se resuelven dp y sp, así TODO (texto, tarjetas, márgenes, widgets) escala a la vez.
 */
abstract class BaseActivity : Activity() {

    /** Zoom con el que se creó esta pantalla; Home compara con el guardado para recrearse al cambiarlo. */
    protected var appliedZoom = 100
        private set

    override fun attachBaseContext(newBase: Context) {
        appliedZoom = LauncherPrefs(newBase).uiZoom.coerceIn(60, 140)
        if (appliedZoom == 100) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.densityDpi = (cfg.densityDpi * appliedZoom / 100f).roundToInt()
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }
}

/** Muestra el fondo actual (fotograma cacheado si es video) detrás de pantallas como Personalizar o Apps abiertas. */
fun BaseActivity.showWallpaperBackdrop(view: ImageView, io: ExecutorService, main: Handler) {
    try {
        io.execute {
            val store = WallpaperStore(this)
            val bmp = store.current()?.let { store.load(it, 1280, 720) } ?: return@execute
            main.post { if (!isFinishing && !isDestroyed) view.setImageBitmap(bmp) }
        }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
    }
}
