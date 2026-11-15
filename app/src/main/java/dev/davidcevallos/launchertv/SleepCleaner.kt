package dev.davidcevallos.launchertv

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Al apagar el TV (pantalla en espera), Android TV no cierra nada: Spotify o YouTube siguen vivos
 * con su servicio en primer plano y ocupando RAM hasta el próximo encendido.
 *
 * Si la opción "Al apagar el TV: cerrar apps" está activa:
 *  1. Detiene lo que suena (sesiones de medios + tecla STOP). Sin esto, el servicio de música
 *     sigue en primer plano y Android no deja cerrarlo.
 *  2. Vuelve a Home, así la app que estaba abierta pasa a segundo plano.
 *  3. Cierra los procesos en segundo plano de las apps lanzables (killBackgroundProcesses).
 *
 * Sin root no se puede forzar el cierre de servicios del sistema: se cierran apps de usuario, no el TV.
 * Se registra desde el launcher y desde el servicio de medios (el que más vive), una sola vez por proceso.
 */
object SleepCleaner {

    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastRun = 0L

    /** Paquetes que nunca se tocan: el propio launcher, la entrada de TV, el control, teclados y la voz. */
    private val KEEP_PREFIXES = listOf(
        "android", "com.android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.tvlauncher", "com.google.android.katniss", "com.google.android.tv.remote",
        "com.google.android.inputmethod", "com.google.android.tungsten",
        "com.tcl.tv", "com.tcl.tvinput", "com.tcl.settings", "com.tcl.autopair", "com.tcl.systemserver",
        "com.tcl.inputmethod", "com.tcl.tcl_bt_rcu_service",
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) run(context.applicationContext)
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        context.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    /** [force]: desde "Cerrar apps ahora" en Personalizar (ignora la opción y no salta a Home). */
    fun run(ctx: Context, force: Boolean = false) {
        if (!force && !LauncherPrefs(ctx).closeAppsOnSleep) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < 5_000) return // algunos TV mandan SCREEN_OFF dos veces
        lastRun = now

        stopMedia(ctx)
        if (!force) goHome(ctx)
        // Dar tiempo a que las apps de música suelten su servicio en primer plano antes de cerrar.
        main.postDelayed({ killBackground(ctx) }, 2_500)
        main.postDelayed({ killBackground(ctx) }, 8_000)
    }

    private fun stopMedia(ctx: Context) {
        runCatching { MediaHub.stopAll() }
        // Sin acceso a notificaciones igual funciona: la tecla STOP va a la sesión de medios activa.
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        listOf(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PAUSE).forEach { code ->
            runCatching {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
    }

    private fun goHome(ctx: Context) = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun killBackground(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/').orEmpty()
        AppRepository(ctx).launchablePackages()
            .filter { pkg -> pkg != ctx.packageName && pkg != ime && KEEP_PREFIXES.none { pkg == it || pkg.startsWith(it) } }
            .forEach { pkg -> runCatching { am.killBackgroundProcesses(pkg) } }
    }
}
