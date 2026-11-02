package dev.davidcevallos.launchertv

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Home. Arriba los widgets (reloj, fecha, saludo, clima, reproductor) y abajo el menú por secciones.
 *
 *  - ▲ desde la primera fila oculta el menú: queda el fondo completo con reloj grande y centrado, clima y
 *    reproductor (◀ ▶ OK controlan la música; ▼ o Atrás abren el menú de nuevo).
 *  - ◀ sobre la primera app muestra la tarjeta "Editar" (escondida el resto del tiempo); también la tecla MENÚ.
 *  - OK largo sobre una app activa el modo mover: ◀ ▶ la reordenan, ▲ ▼ la pasan de sección, OK suelta, MENÚ la quita.
 */
class MainActivity : BaseActivity() {

    private lateinit var dock: LinearLayout
    private lateinit var dockScroll: ScrollView
    private lateinit var scrim: View
    private lateinit var moveHint: TextView
    private lateinit var cleanHint: TextView
    private lateinit var greeting: View
    private lateinit var welcomeLine: View
    private lateinit var headerView: View
    private lateinit var showcaseView: View
    private lateinit var header: HeaderController
    private lateinit var showcase: HeaderController
    private lateinit var chip: NowPlayingCard
    private lateinit var bigCard: NowPlayingCard
    private lateinit var wallpapers: WallpaperRotator
    private lateinit var tiles: TileFactory
    private lateinit var apps: AppRepository
    private lateinit var openApps: OpenAppsRepository
    private lateinit var prefs: LauncherPrefs

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val tileByPkg = HashMap<String, TileFactory.Tile>()
    private val sectionRows = ArrayList<LinearLayout>() // una por sección del usuario, en orden
    private var editTile: TileFactory.Tile? = null
    private var lastFocusKey: String? = null
    private var running: Map<String, Boolean> = emptyMap()

    private var greetingShowing = false
    private var menuHidden = false
    private var moveView: View? = null

    private val trackListener: (Track?) -> Unit = { t ->
        chip.bind(t)
        bigCard.bind(t)
        refreshLive(t)
        if (menuHidden && t == null) showMenu() // dejó de sonar: no quedan controles que usar
    }

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
        headerView = findViewById(R.id.header)
        showcaseView = findViewById(R.id.showcase)
        prefs = LauncherPrefs(this)
        tiles = TileFactory(this)
        apps = AppRepository(this)
        openApps = OpenAppsRepository(this, apps)

        header = HeaderController(this, headerView)
        showcase = HeaderController(this, showcaseView, scale = 1.8f, details = false, fetchWeather = false)
        header.onWeather = { showcase.setWeather(it) }

        chip = NowPlayingCard(this, big = false)
        bigCard = NowPlayingCard(this, big = true)
        findViewById<FrameLayout>(R.id.chipHolder).addView(chip.view)
        findViewById<LinearLayout>(R.id.showcaseCards).addView(
            bigCard.view, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(24f) },
        )

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
        if (appliedZoom != prefs.uiZoom.coerceIn(60, 140)) { // cambió el zoom en Personalizar
            recreate()
            return
        }
        dockScroll.background = Theme.glassDrawable(this)
        moveHint.background = Theme.glassDrawable(this, 24f)
        welcomeLine.setBackgroundColor(Theme.accent(this))
        showMenu(animate = false)
        buildDock() // barato: pocas tarjetas; así reflejas instalaciones/desinstalaciones sin listeners
        header.start()
        showcase.start()
        wallpapers.start()
        MediaHub.start(this)
        MediaHub.addListener(trackListener)
        showGreeting()
        if (prefs.pendingMoveMode) {
            prefs.pendingMoveMode = false
            sectionRows.firstOrNull()?.let { r -> firstMovable(r)?.let { startMove(it) } }
        }
        scheduleAutoHide()
    }

    override fun onPause() {
        super.onPause()
        header.stop()
        showcase.stop()
        MediaHub.removeListener(trackListener)
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
        showcase.release()
        wallpapers.release()
        io.shutdownNow()
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
                menuHidden -> if (isNavKey(k)) {
                    val controls = bigCard.isShown &&
                        (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
                            k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
                    if (controls) return super.dispatchKeyEvent(e) // ◀ ▶ OK mueven el foco entre los botones del reproductor
                    if (k == KeyEvent.KEYCODE_DPAD_UP) return true
                    showMenu()
                    return true
                }
                moveView != null && handleMoveKey(e) -> return true
                moveView == null && k == KeyEvent.KEYCODE_DPAD_UP && e.repeatCount == 0 && focusedBlockIndex() == 0 -> {
                    hideMenu()
                    return true
                }
                moveView == null && k == KeyEvent.KEYCODE_DPAD_LEFT && revealEditIfOnFirstApp() -> return true
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
        dockScroll.animate().alpha(0f).translationY(dockScroll.height + dpi(40f).toFloat())
            .setDuration(380L)
            .withEndAction { if (menuHidden) dockScroll.visibility = View.INVISIBLE }
            .start()
        scrim.animate().alpha(0.55f).setDuration(380L).start()
        // La cabecera compacta se funde con la vista grande y centrada.
        headerView.animate().alpha(0f).setDuration(300L)
            .withEndAction { if (menuHidden) headerView.visibility = View.INVISIBLE }.start()
        showcaseView.animate().cancel()
        showcaseView.visibility = View.VISIBLE
        showcaseView.animate().alpha(1f).setDuration(420L).start()
        if (bigCard.isShown) bigCard.primaryFocus.requestFocus()
        cleanHint.setText(if (bigCard.isShown) R.string.clean_hint_player else R.string.clean_hint)
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
        headerView.visibility = View.VISIBLE
        if (animate && wasHidden) {
            dockScroll.animate().alpha(1f).translationY(0f).setDuration(300L).start()
            scrim.animate().alpha(1f).setDuration(300L).start()
            headerView.animate().alpha(1f).setDuration(300L).start()
            showcaseView.animate().alpha(0f).setDuration(250L)
                .withEndAction { if (!menuHidden) showcaseView.visibility = View.GONE }.start()
        } else {
            dockScroll.animate().cancel()
            dockScroll.alpha = 1f
            dockScroll.translationY = 0f
            scrim.alpha = 1f
            headerView.animate().cancel()
            headerView.alpha = 1f
            showcaseView.animate().cancel()
            showcaseView.alpha = 0f
            showcaseView.visibility = View.GONE
        }
        if (wasHidden) restoreFocus()
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

    // ---------- Menú por secciones ----------

    private fun tileWidthDp(): Float = (unzoomedWidthDp() * 0.18f * prefs.tileScale / 100f).coerceIn(140f, 300f)

    private fun isMovable(v: View?): Boolean {
        val t = v?.tag as? String ?: return false
        return !t.startsWith("sys:") && t != "ph" && v.visibility == View.VISIBLE
    }

    private fun firstMovable(row: LinearLayout): View? =
        (0 until row.childCount).map { row.getChildAt(it) }.firstOrNull { isMovable(it) }

    private fun buildDock() {
        dock.removeAllViews()
        tileByPkg.clear()
        sectionRows.clear()
        moveView = null
        moveHint.visibility = View.GONE
        val w = tileWidthDp()
        val gap = dpi(24f)
        // Qué apps están en uso en segundo plano: unas decenas de consultas al sistema, fuera del hilo principal.
        try {
            io.execute {
                val r = runCatching { openApps.runningPackages() }.getOrDefault(emptyMap())
                main.post { running = r; refreshLive(MediaHub.track) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }

        val sections = apps.sections()
        sections.forEachIndexed { i, section ->
            val row = newRow()
            sectionRows += row
            if (i == 0) {
                // "Editar" vive escondida al inicio de la primera fila: aparece con ◀ sobre la primera app.
                val edit = tiles.actionTile(getString(R.string.edit), getDrawable(R.drawable.ic_edit), w) { openCustomize() }
                edit.root.tag = "sys:edit"
                edit.root.visibility = View.GONE
                editTile = edit
                addTile(row, edit.root, gap)
                val previous = edit.root.onFocusChangeListener
                edit.root.setOnFocusChangeListener { v, has ->
                    previous?.onFocusChange(v, has)
                    if (!has) main.post { if (!v.hasFocus() && !noAppsInFirstRow()) v.visibility = View.GONE }
                }
            }
            val entries = section.packages.mapNotNull { apps.resolvePackage(it) }
            entries.forEach { entry ->
                lateinit var tile: TileFactory.Tile
                tile = tiles.appTile(
                    entry, w, live = isLive(entry.packageName, MediaHub.track),
                    onLongClick = { startMove(tile.root) },
                ) { launch(entry.intent) }
                tile.root.tag = entry.packageName
                tileByPkg[entry.packageName] = tile
                addTile(row, tile.root, gap)
            }
            if (entries.isEmpty()) {
                row.addView(placeholder())
                if (i == 0) editTile?.root?.visibility = View.VISIBLE // sin apps no habría sobre qué pulsar ◀
            }
            addBlock(section.title, row)
        }

        val system = newRow()
        fun sys(key: String, label: Int, icon: Int, action: () -> Unit) {
            val t = tiles.actionTile(getString(label), getDrawable(icon), w, action)
            t.root.tag = "sys:$key"
            addTile(system, t.root, gap)
        }
        sys("apps", R.string.all_apps, R.drawable.ic_grid) { startActivity(Intent(this, AllAppsActivity::class.java)) }
        sys("open", R.string.open_apps, R.drawable.ic_layers) { startActivity(Intent(this, OpenAppsActivity::class.java)) }
        sys("walls", R.string.wallpapers, R.drawable.ic_wallpaper) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        sys("settings", R.string.settings, R.drawable.ic_settings) {
            launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addBlock(getString(R.string.system_section), system)

        dock.post { fitPanelHeight() }
        if (!menuHidden) restoreFocus()
    }

    private fun noAppsInFirstRow(): Boolean = sectionRows.firstOrNull()?.let { firstMovable(it) == null } ?: true

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dpi(32f), dpi(8f), dpi(32f), dpi(6f))
    }

    private fun placeholder() = TextView(this).apply {
        tag = "ph"
        text = getString(R.string.section_empty)
        textSize = 16f
        setTextColor(0x99FFFFFF.toInt())
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dpi(110f)
    }

    /** Título + fila que se desplaza en horizontal. */
    private fun addBlock(title: String, row: LinearLayout) {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(TextView(this).apply {
            text = title
            textSize = 15f
            letterSpacing = 0.08f
            setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(36f); topMargin = dpi(2f) })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dpi(40f))
            addView(row)
        }
        block.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        dock.addView(block, LinearLayout.LayoutParams(-1, -2))
    }

    /** El panel muestra una fila entera y asoma la siguiente (para que se note que hay más abajo). */
    private fun fitPanelHeight() {
        val first = dock.getChildAt(0) ?: return
        if (first.height == 0) {
            main.postDelayed({ fitPanelHeight() }, 50L)
            return
        }
        val peek = if (dock.childCount > 1) dpi(46f) else 0
        dockScroll.layoutParams = dockScroll.layoutParams.apply {
            height = first.height + dock.paddingTop + dock.paddingBottom + peek
        }
    }

    private fun addTile(row: LinearLayout, view: View, gap: Int) {
        val original = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, has ->
            original?.onFocusChange(v, has)
            if (has) {
                lastFocusKey = v.tag as? String
                // La fila actual de la tarjeta (puede haber cambiado de sección al moverla), no la original.
                snapToBlockOf(v.parent as? LinearLayout ?: row)
            }
        }
        row.addView(view, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
    }

    /** Al cambiar de fila, el panel desliza hasta ella: siempre se ve la fila activa con su título. */
    private fun snapToBlockOf(row: LinearLayout) {
        val block = row.parent?.parent as? View ?: return
        dockScroll.post { dockScroll.smoothScrollTo(0, block.top) }
    }

    private fun focusedBlockIndex(): Int {
        var v: View? = currentFocus
        while (v != null && v.parent !== dock) v = v.parent as? View
        return if (v == null) -1 else dock.indexOfChild(v)
    }

    private fun restoreFocus() {
        val key = lastFocusKey
        val rows = ArrayList<LinearLayout>(sectionRows)
        findRow(dock.getChildAt(dock.childCount - 1))?.let { rows += it }
        val target = rows.asSequence()
            .flatMap { r -> (0 until r.childCount).asSequence().map { r.getChildAt(it) } }
            .firstOrNull { it.tag == key && it.visibility == View.VISIBLE }
            ?: sectionRows.firstOrNull()?.let { firstMovable(it) }
            ?: sectionRows.firstOrNull()?.getChildAt(0)
        target?.requestFocus()
    }

    private fun findRow(block: View?): LinearLayout? =
        ((block as? LinearLayout)?.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout

    /** ◀ sobre la primera app de la primera fila: aparece "Editar" y toma el foco. */
    private fun revealEditIfOnFirstApp(): Boolean {
        val edit = editTile?.root ?: return false
        val first = sectionRows.firstOrNull()?.let { firstMovable(it) } ?: return false
        if (currentFocus !== first || edit.visibility == View.VISIBLE) return false
        edit.alpha = 0f
        edit.visibility = View.VISIBLE
        edit.animate().alpha(1f).setDuration(200L).start()
        edit.requestFocus()
        return true
    }

    /** Punto "en uso": la app tiene un servicio en primer plano o es la que suena. */
    private fun isLive(pkg: String, t: Track?): Boolean = running[pkg] == true || t?.pkg == pkg

    private fun refreshLive(t: Track?) {
        tileByPkg.forEach { (pkg, tile) -> tile.setLive(isLive(pkg, t)) }
    }

    // ---------- Modo mover ----------

    private fun startMove(view: View) {
        if (!isMovable(view)) return
        val pkg = view.tag as String
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
            KeyEvent.KEYCODE_DPAD_UP -> moveSection(v, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveSection(v, +1)
            KeyEvent.KEYCODE_MENU -> removeMoving(v)
            // repeatCount > 0 = sigues sosteniendo el OK que activó el modo: no lo sueltes todavía.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) stopMove()
            else -> return false
        }
        return true
    }

    /** Intercambia la tarjeta con su vecina de fila; no se puede cruzar las tarjetas fijas. */
    private fun moveBy(v: View, dir: Int) {
        val row = v.parent as? LinearLayout ?: return
        val target = row.indexOfChild(v) + dir
        if (!isMovable(row.getChildAt(target))) return
        val lp = v.layoutParams
        row.removeView(v)
        row.addView(v, target, lp)
        v.requestFocus()
        persistOrder()
    }

    /** Pasa la tarjeta a la sección de arriba o de abajo, en la misma columna si se puede. */
    private fun moveSection(v: View, dir: Int) {
        val row = v.parent as? LinearLayout ?: return
        val from = sectionRows.indexOf(row)
        val to = from + dir
        if (from < 0 || to !in sectionRows.indices) return
        val target = sectionRows[to]
        val lp = v.layoutParams
        val col = row.indexOfChild(v)
        row.removeView(v)
        updatePlaceholder(row)
        (0 until target.childCount).map { target.getChildAt(it) }.firstOrNull { it.tag == "ph" }?.let { target.removeView(it) }
        val floor = if (to == 0) 1 else 0 // en la primera fila el 0 es "Editar"
        target.addView(v, col.coerceIn(floor, target.childCount), lp)
        v.requestFocus()
        persistOrder()
    }

    private fun updatePlaceholder(row: LinearLayout) {
        val hasPlaceholder = (0 until row.childCount).any { row.getChildAt(it).tag == "ph" }
        if (firstMovable(row) == null && !hasPlaceholder) row.addView(placeholder())
    }

    private fun removeMoving(v: View) {
        val pkg = v.tag as? String ?: return
        val label = v.contentDescription
        apps.unpin(pkg)
        Toast.makeText(this, getString(R.string.unpinned, label), Toast.LENGTH_SHORT).show()
        buildDock()
        moveView = null
        moveHint.visibility = View.GONE
        scheduleAutoHide()
    }

    /** Guarda el orden y la sección de cada tarjeta tal como quedaron en pantalla. */
    private fun persistOrder() {
        val list = apps.sections()
        sectionRows.forEachIndexed { i, row ->
            val section = list.getOrNull(i) ?: return@forEachIndexed
            val shown = (0 until row.childCount).map { row.getChildAt(it) }.filter { isMovable(it) }.map { it.tag as String }
            // Las apps guardadas que ya no están instaladas se conservan al final: si vuelven, vuelven a su sitio.
            val missing = section.packages.filter { !apps.hasLauncher(it) && it !in shown }
            section.packages.clear()
            section.packages.addAll(shown + missing)
        }
        apps.saveSections(list)
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

    private companion object {
        /** Vive mientras viva el proceso: se resetea al reiniciar el TV, al apagarlo o al apagar la pantalla. */
        @Volatile
        var greeted = false
    }
}
