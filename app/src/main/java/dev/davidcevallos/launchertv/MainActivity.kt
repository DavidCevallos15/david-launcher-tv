package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Home. De arriba abajo: widgets (reloj, fecha, saludo, clima) y el dock.
 *
 *  - ▲ en el dock oculta el menú y deja el fondo completo con los widgets; cualquier botón lo vuelve a abrir.
 *  - OK largo sobre una app del dock activa el modo mover (◀ ▶ mueven, OK suelta, ▲ la quita).
 *  - La tarjeta "Editar" (o la tecla MENÚ) abre Personalizar.
 */
class MainActivity : Activity() {

    private lateinit var dock: LinearLayout
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var scrim: View
    private lateinit var moveHint: TextView
    private lateinit var cleanHint: TextView
    private lateinit var greeting: View
    private lateinit var welcomeLine: View
    private lateinit var header: HeaderController
    private lateinit var wallpapers: WallpaperRotator
    private lateinit var tiles: TileFactory
    private lateinit var apps: AppRepository
    private lateinit var prefs: LauncherPrefs

    private val main = Handler(Looper.getMainLooper())
    private val tileByPkg = HashMap<String, TileFactory.Tile>()
    private var lastFocusIndex = 1 // 0 es la tarjeta "Editar": el foco empieza en la primera app

    private var greetingShowing = false
    private var menuHidden = false
    private var moveView: View? = null

    /** Al apagar el TV el proceso sigue vivo: se vuelve a saludar al encenderlo. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            greeted = false
            prefs.screenOffSinceGreet = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dock = findViewById(R.id.dock)
        dockScroll = findViewById(R.id.dockScroll)
        scrim = findViewById(R.id.scrim)
        moveHint = findViewById(R.id.moveHint)
        cleanHint = findViewById(R.id.cleanHint)
        greeting = findViewById(R.id.greeting)
        welcomeLine = findViewById(R.id.welcomeLine)
        prefs = LauncherPrefs(this)
        tiles = TileFactory(this)
        apps = AppRepository(this)
        header = HeaderController(this, findViewById(R.id.header))
        wallpapers = WallpaperRotator(
            this,
            findViewById<ImageView>(R.id.wallFront),
            findViewById<ImageView>(R.id.wallBack),
            findViewById<TextureView>(R.id.wallVideo),
        )
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onResume() {
        super.onResume()
        dockScroll.background = Theme.glassDrawable(this)
        moveHint.background = Theme.glassDrawable(this, 24f)
        welcomeLine.setBackgroundColor(Theme.accent(this))
        showMenu(animate = false)
        buildDock() // barato: pocas tarjetas; así reflejas instalaciones/desinstalaciones sin listeners
        header.start()
        wallpapers.start()
        showGreeting()
        if (prefs.pendingMoveMode) {
            prefs.pendingMoveMode = false
            dock.getChildAt(1)?.takeIf { it.tag is String }?.let { startMove(it) }
        }
        scheduleAutoHide()
    }

    override fun onPause() {
        super.onPause()
        header.stop()
        main.removeCallbacks(autoHide)
        // Si el sistema nos pausa a mitad de la bienvenida (splash, otra app), no se queda pegada:
        // se oculta y se vuelve a mostrar completa en el próximo onResume.
        if (greetingShowing) {
            main.removeCallbacks(hideGreeting)
            greeting.animate().cancel()
            greeting.visibility = View.GONE
            greetingShowing = false
            greeted = false
        }
        if (moveView != null) stopMove()
        wallpapers.stop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        header.release()
        wallpapers.release()
        super.onDestroy()
    }

    /** Un launcher no "sale": Back sale del modo mover o reabre el menú, pero nunca cierra Home. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            moveView != null -> stopMove()
            menuHidden -> showMenu()
            else -> Unit
        }
    }

    // ---------- Teclas ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            scheduleAutoHide()
            val k = e.keyCode
            when {
                menuHidden && isNavKey(k) -> { showMenu(); return true }
                moveView != null && handleMoveKey(e) -> return true
                moveView == null && !menuHidden && k == KeyEvent.KEYCODE_DPAD_UP && e.repeatCount == 0 -> {
                    hideMenu()
                    return true
                }
                k == KeyEvent.KEYCODE_MENU && moveView == null -> {
                    openCustomize()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun isNavKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
        k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
        k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU

    // ---------- Ocultar / mostrar el menú ----------

    private fun hideMenu() {
        if (menuHidden) return
        menuHidden = true
        main.removeCallbacks(autoHide)
        dockScroll.animate().alpha(0f).translationY(dockScroll.height + dp(40f).toFloat())
            .setDuration(380L)
            .withEndAction { if (menuHidden) dockScroll.visibility = View.INVISIBLE }
            .start()
        scrim.animate().alpha(0.55f).setDuration(380L).start()
        cleanHint.animate().cancel()
        cleanHint.animate().alpha(1f).setDuration(300L).start()
        main.removeCallbacks(fadeCleanHint)
        main.postDelayed(fadeCleanHint, 3_000L)
    }

    private val fadeCleanHint = Runnable { cleanHint.animate().alpha(0f).setDuration(600L).start() }

    private fun showMenu(animate: Boolean = true) {
        val wasHidden = menuHidden
        menuHidden = false
        main.removeCallbacks(fadeCleanHint)
        cleanHint.animate().cancel()
        cleanHint.alpha = 0f
        dockScroll.visibility = View.VISIBLE
        if (animate && wasHidden) {
            dockScroll.animate().alpha(1f).translationY(0f).setDuration(300L).start()
            scrim.animate().alpha(1f).setDuration(300L).start()
        } else {
            dockScroll.animate().cancel()
            dockScroll.alpha = 1f
            dockScroll.translationY = 0f
            scrim.alpha = 1f
        }
        if (wasHidden) dock.getChildAt(lastFocusIndex.coerceIn(0, dock.childCount - 1))?.requestFocus()
        scheduleAutoHide()
    }

    private val autoHide = Runnable { if (moveView == null && !greetingShowing) hideMenu() }

    private fun scheduleAutoHide() {
        main.removeCallbacks(autoHide)
        val s = prefs.autoHideSeconds
        if (s > 0 && !menuHidden && moveView == null) main.postDelayed(autoHide, s * 1000L)
    }

    // ---------- Bienvenida ----------

    /** Breve y solo al arrancar el TV o el launcher (o al encender el TV): al volver de otras apps no molesta. */
    private fun showGreeting() {
        if (greeted || !prefs.welcome) return
        // El sistema recicla el proceso del launcher cuando abres otras apps: sin esta comprobación se saludaría
        // en cada vuelta a Home. Solo se saluda tras un arranque nuevo del TV, tras apagar la pantalla o pasadas 6 h.
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val newBoot = kotlin.math.abs(bootAt - prefs.lastGreetBoot) > 120_000L
        val stale = System.currentTimeMillis() - prefs.lastGreetAt > 6 * 3_600_000L
        if (!(newBoot || stale || prefs.screenOffSinceGreet)) {
            greeted = true
            return
        }
        greeted = true
        greetingShowing = true
        greeting.visibility = View.VISIBLE
        greeting.alpha = 0f
        greeting.animate().alpha(1f).setDuration(450L).start()
        main.postDelayed(hideGreeting, 2_000L)
    }

    private val hideGreeting = Runnable {
        greeting.animate().alpha(0f).setDuration(600L)
            .withEndAction {
                greeting.visibility = View.GONE
                greetingShowing = false
                // Solo se da por saludado si la bienvenida se vio completa.
                prefs.lastGreetAt = System.currentTimeMillis()
                prefs.lastGreetBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                prefs.screenOffSinceGreet = false
            }.start()
    }

    // ---------- Dock ----------

    private fun tileWidthDp(): Float = (resources.configuration.screenWidthDp * 0.18f).coerceIn(170f, 250f)

    private fun buildDock() {
        dock.removeAllViews()
        tileByPkg.clear()
        moveView = null
        moveHint.visibility = View.GONE
        val gap = dp(24f)
        val w = tileWidthDp()

        dock.addSpaced(
            tiles.actionTile(getString(R.string.edit), getDrawable(R.drawable.ic_edit), w) { openCustomize() }.root,
            gap,
        )

        apps.dockApps().forEach { entry ->
            lateinit var tile: TileFactory.Tile
            tile = tiles.appTile(entry, w, onLongClick = { startMove(tile.root) }) { launch(entry.intent) }
            tile.root.tag = entry.packageName
            tileByPkg[entry.packageName] = tile
            dock.addSpaced(tile.root, gap)
        }

        dock.addSpaced(
            tiles.actionTile(getString(R.string.all_apps), getDrawable(R.drawable.ic_grid), w) {
                startActivity(Intent(this, AllAppsActivity::class.java))
            }.root, gap,
        )
        dock.addSpaced(
            tiles.actionTile(getString(R.string.wallpapers), getDrawable(R.drawable.ic_wallpaper), w) {
                startActivity(Intent(this, WallpaperActivity::class.java))
            }.root, gap,
        )
        dock.addSpaced(
            tiles.actionTile(getString(R.string.settings), getDrawable(R.drawable.ic_settings), w) {
                launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.root, gap,
        )

        if (!menuHidden) dock.getChildAt(lastFocusIndex.coerceIn(0, dock.childCount - 1))?.requestFocus()
    }

    private fun LinearLayout.addSpaced(view: View, gap: Int) {
        view.setOnFocusChangeListener(chain(view.onFocusChangeListener) { lastFocusIndex = indexOfChild(view) })
        addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = gap })
    }

    private fun chain(
        original: View.OnFocusChangeListener?,
        onFocus: () -> Unit,
    ) = View.OnFocusChangeListener { v, has ->
        original?.onFocusChange(v, has)
        if (has) onFocus()
    }

    // ---------- Modo mover ----------

    private fun startMove(view: View) {
        val pkg = view.tag as? String ?: return
        moveView = view
        view.requestFocus() // el aro de foco acompaña a la tarjeta que se mueve
        tileByPkg[pkg]?.setMoving(true)
        moveHint.text = getString(R.string.move_hint, view.contentDescription)
        moveHint.visibility = View.VISIBLE
        main.removeCallbacks(autoHide)
    }

    private fun stopMove() {
        val v = moveView ?: return
        (v.tag as? String)?.let { tileByPkg[it]?.setMoving(false) }
        moveView = null
        moveHint.visibility = View.GONE
        persistOrder()
        scheduleAutoHide()
    }

    private fun handleMoveKey(e: KeyEvent): Boolean {
        val v = moveView ?: return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(v, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(v, +1)
            KeyEvent.KEYCODE_DPAD_UP -> removeMoving(v)
            KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            // repeatCount > 0 = sigues sosteniendo el OK que activó el modo: no lo sueltes todavía.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) stopMove()
            else -> return false
        }
        return true
    }

    /** Intercambia la tarjeta con su vecina; no se puede cruzar las tarjetas fijas (Editar, Apps, Fondos, Ajustes). */
    private fun moveBy(v: View, dir: Int) {
        val target = dock.indexOfChild(v) + dir
        val other = dock.getChildAt(target) ?: return
        if (other.tag !is String) return
        val lp = v.layoutParams
        dock.removeView(v)
        dock.addView(v, target, lp)
        v.requestFocus()
        persistOrder()
    }

    private fun removeMoving(v: View) {
        val pkg = v.tag as? String ?: return
        val label = v.contentDescription
        apps.unpin(pkg)
        Toast.makeText(this, getString(R.string.unpinned, label), Toast.LENGTH_SHORT).show()
        buildDock()
        stopMoveUi()
    }

    private fun stopMoveUi() {
        moveView = null
        moveHint.visibility = View.GONE
        scheduleAutoHide()
    }

    private fun persistOrder() {
        apps.setDockOrder((0 until dock.childCount).mapNotNull { dock.getChildAt(it).tag as? String })
    }

    // ---------- Varios ----------

    private fun openCustomize() = startActivity(Intent(this, CustomizeActivity::class.java))

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private companion object {
        /** Vive mientras viva el proceso: se resetea al reiniciar el TV, al apagarlo o al apagar la pantalla. */
        @Volatile
        var greeted = false
    }
}
