package dev.davidcevallos.tvremote.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.davidcevallos.tvremote.R
import dev.davidcevallos.tvremote.core.Keys
import dev.davidcevallos.tvremote.core.PairingSession
import dev.davidcevallos.tvremote.core.Remote
import dev.davidcevallos.tvremote.core.RemoteSession
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class MainActivity : Activity() {

    // ---------- Estado ----------
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private val identity by lazy { IdentityStore.loadOrCreate(this) }
    private var lastError: Throwable? = null
    private lateinit var discovery: TvDiscovery

    @Volatile private var session: RemoteSession? = null
    private var ready = false
    private var started = false
    private var retries = 0
    private var volumeText = ""
    private var voice: VoiceStreamer? = null
    private var micHeld = false
    private var dictationTarget = DICTATE_TO_TV

    private val host get() = prefs.getString(K_HOST, null)

    // ---------- Vistas ----------
    private lateinit var setup: View
    private lateinit var remote: View
    private lateinit var typePanel: View
    private lateinit var typeField: EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvName: TextView
    private lateinit var imeHint: TextView
    private lateinit var found: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setup = findViewById(R.id.setup)
        remote = findViewById(R.id.remote)
        typePanel = findViewById(R.id.typePanel)
        typeField = findViewById(R.id.typeField)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        tvName = findViewById(R.id.tvName)
        imeHint = findViewById(R.id.imeHint)
        found = findViewById(R.id.found)

        discovery = TvDiscovery(this) { name, ip -> addFoundTv(name, ip) }
        bindSetup()
        bindRemote()
        bindKeyboard()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (host == null) showSetup() else { showRemote(); connect() }
    }

    override fun onStop() {
        super.onStop()
        started = false
        discovery.stop()
        stopVoice()
        disconnect()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ======================================================================
    // Setup y emparejamiento
    // ======================================================================

    private fun bindSetup() {
        val ip = findViewById<EditText>(R.id.ip)
        val go = { ip.text.toString().trim().takeIf { it.isNotEmpty() }?.let { selectTv(it, it) } }
        findViewById<Button>(R.id.connectIp).setOnClickListener { go() }
        ip.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    private fun showSetup() {
        setup.visibility = View.VISIBLE
        remote.visibility = View.GONE
        typePanel.visibility = View.GONE
        found.removeAllViews()
        discovery.start()
    }

    private fun addFoundTv(name: String, ip: String) {
        for (i in 0 until found.childCount) if (found.getChildAt(i).tag == ip) return
        found.addView(Button(this, null, 0, R.style.Chip).apply {
            tag = ip
            text = "$name  ·  $ip"
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) }
            setOnClickListener { selectTv(name, ip) }
        })
    }

    private fun selectTv(name: String, ip: String) {
        discovery.stop()
        prefs.edit().putString(K_HOST, ip).putString(K_NAME, name).remove(K_PIN).apply()
        hideKeyboard()
        showRemote()
        connect()
    }

    private fun startPairing() {
        val ip = host ?: return
        setStatus(Status.CONNECTING, getString(R.string.connecting))
        io.execute {
            val pairing = runCatching {
                PairingSession.start(ip, identity, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            }
            main.post {
                pairing.onSuccess { askPin(it) }
                    .onFailure { fail("No se pudo emparejar", it) }
            }
        }
    }

    private fun askPin(pairing: PairingSession) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            textSize = 28f
            letterSpacing = 0.3f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_msg)
            .setView(input)
            .setPositiveButton(R.string.pair_ok, null)
            .setNegativeButton(R.string.cancel) { _, _ -> io.execute { pairing.close() } }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length != 6) return@setOnClickListener
                io.execute {
                    val ok = runCatching { pairing.finish(pin) }
                    main.post {
                        when {
                            ok.getOrNull() == true -> {
                                prefs.edit().putString(K_PIN, pairing.serverCertSha256).apply()
                                io.execute { pairing.close() }
                                dialog.dismiss()
                                connect()
                            }
                            ok.isSuccess -> { input.error = getString(R.string.pair_bad); input.selectAll() }
                            else -> {
                                dialog.dismiss()
                                fail("El TV rechazó el emparejamiento", ok.exceptionOrNull())
                            }
                        }
                    }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    // ======================================================================
    // Conexión
    // ======================================================================

    private fun connect() {
        val ip = host ?: return showSetup()
        if (session != null) return
        ready = false
        tvName.text = prefs.getString(K_NAME, ip)
        setStatus(Status.CONNECTING, getString(R.string.connecting))
        io.execute {
            try {
                session = RemoteSession.connect(ip, identity, prefs.getString(K_PIN, null), listener, model = Build.MODEL)
            } catch (e: SSLException) {
                main.post { if (started) startPairing() } // TV no reconoce este celular o cambió su cert
            } catch (e: Exception) {
                main.post { lastError = e; scheduleRetry() }
            }
        }
    }

    private fun disconnect() {
        val s = session ?: return
        session = null
        io.execute { s.close() }
    }

    private fun scheduleRetry() {
        if (!started) return
        if (retries++ < 3) {
            setStatus(Status.CONNECTING, getString(R.string.connecting))
            main.postDelayed({ if (started && session == null) connect() }, 1_500L * retries)
        } else {
            if (lastError != null) fail("Sin conexión con el TV", lastError)
            else setStatus(Status.OFFLINE, getString(R.string.offline))
        }
    }

    private val listener = object : RemoteSession.Listener {
        override fun onReady(tvModel: String, features: Int) = main.post {
            ready = true
            retries = 0
            lastError = null
            if (prefs.getString(K_NAME, null) == host && tvModel.isNotEmpty()) {
                prefs.edit().putString(K_NAME, tvModel).apply()
                tvName.text = tvModel
            }
            setStatus(Status.OK, getString(R.string.connected))
        }.let { }

        override fun onVolume(level: Int, max: Int, muted: Boolean) = main.post {
            volumeText = if (muted) "silenciado" else "vol $level"
            if (ready) setStatus(Status.OK, getString(R.string.connected))
        }.let { }

        override fun onImeField(active: Boolean) = main.post {
            imeHint.text = if (active) "El TV espera texto" else ""
            if (active && typePanel.visibility != View.VISIBLE) openKeyboard()
        }.let { }

        override fun onDisconnected(error: Throwable?) = main.post {
            val wasReady = ready
            session = null
            ready = false
            when {
                !started -> Unit
                !wasReady && error is SSLException -> startPairing() // rechazado en el 1er mensaje (TLS 1.3)
                else -> scheduleRetry()
            }
        }.let { }
    }

    // ======================================================================
    // Control
    // ======================================================================

    private fun showRemote() {
        setup.visibility = View.GONE
        remote.visibility = View.VISIBLE
    }

    private fun send(code: Int, direction: Int = Remote.SHORT) {
        val s = session
        if (s == null) { connect(); return }
        s.key(code, direction)
    }

    private fun bindRemote() {
        findViewById<TouchpadView>(R.id.pad).onKey = { code, dir -> send(code, dir) }

        fun key(id: Int, code: Int) = findViewById<ImageButton>(id).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            send(code)
        }
        key(R.id.back, Keys.BACK)
        key(R.id.home, Keys.HOME)
        key(R.id.playPause, Keys.MEDIA_PLAY_PAUSE)
        key(R.id.mute, Keys.VOLUME_MUTE)
        key(R.id.power, Keys.POWER)
        repeatable(R.id.volUp, Keys.VOLUME_UP)
        repeatable(R.id.volDown, Keys.VOLUME_DOWN)

        findViewById<ImageButton>(R.id.keyboard).setOnClickListener { openKeyboard() }
        bindMic(findViewById(R.id.mic))

        findViewById<View>(R.id.statusBox).setOnClickListener {
            when {
                session != null -> confirmChangeTv()
                lastError != null -> showErrorDetail()
                else -> { retries = 0; connect() }
            }
        }

        val apps = findViewById<LinearLayout>(R.id.apps)
        SHORTCUTS.forEach { (label, target) ->
            apps.addView(Button(this, null, 0, R.style.Chip).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) }
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    when (target) {
                        is Int -> send(target)
                        is String -> session?.launch(target) ?: connect()
                    }
                }
            })
        }
    }

    /** Mantener presionado repite (útil para el volumen). */
    private fun repeatable(id: Int, code: Int) {
        val view = findViewById<ImageButton>(id)
        val repeat = object : Runnable {
            override fun run() { send(code); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); main.postDelayed(this, 120) }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; send(code); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); main.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(repeat) }
            }
            true
        }
    }

    private fun confirmChangeTv() {
        AlertDialog.Builder(this)
            .setTitle(R.string.change_tv)
            .setMessage("¿Desconectar de ${tvName.text} y buscar otro TV?")
            .setPositiveButton(R.string.change_tv) { _, _ ->
                disconnect()
                prefs.edit().clear().apply()
                showSetup()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Teclas de volumen físicas del celular controlan el TV mientras la app está abierta. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Keys.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Keys.VOLUME_DOWN
            else -> return super.dispatchKeyEvent(event)
        }
        if (session == null || remote.visibility != View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) send(code)
        return true
    }

    // ======================================================================
    // Voz
    // ======================================================================

    /**
     * Mantener presionado:
     *  - si el TV soporta voz → el audio va al TV (búsqueda/Asistente del TV, como el control físico)
     *  - si no, o sin permiso de mic → dictado del celular y el texto se escribe en el TV
     */
    private fun bindMic(button: ImageButton) {
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    micHeld = true
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startVoice()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    micHeld = false
                    stopVoice()
                }
            }
            true
        }
    }

    private fun startVoice() {
        val s = session ?: return connect()
        if (!s.voiceSupported) { micHeld = false; dictate(DICTATE_TO_TV); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micHeld = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        statusText.text = getString(R.string.listening)
        s.startVoice { id ->
            main.post {
                when {
                    id == null -> { setStatus(Status.OK, getString(R.string.connected)); dictate(DICTATE_TO_TV) }
                    !micHeld -> s.endVoice(id) // soltó antes de que el TV abriera la sesión
                    else -> voice = VoiceStreamer(s, id).also { it.start() }
                }
            }
        }
    }

    private fun stopVoice() {
        voice?.stop()
        voice = null
        if (ready) setStatus(Status.OK, getString(R.string.connected))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == REQ_MIC && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_SHORT).show()
            dictate(DICTATE_TO_TV)
        }
    }

    /** Dictado del sistema (sin permiso de micrófono para esta app: lo maneja el reconocedor de Google). */
    private fun dictate(target: Int) {
        dictationTarget = target
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla para escribir en el TV")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_DICTATE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_stt, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_DICTATE || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        if (dictationTarget == DICTATE_TO_FIELD) {
            typeField.append(if (typeField.text.isEmpty()) text else " $text")
            typeField.setSelection(typeField.text.length)
        } else {
            sendTextToTv(text)
        }
    }

    // ======================================================================
    // Teclado
    // ======================================================================

    private fun bindKeyboard() {
        findViewById<ImageButton>(R.id.sendText).setOnClickListener { submitTyped() }
        findViewById<ImageButton>(R.id.dictate).setOnClickListener { dictate(DICTATE_TO_FIELD) }
        findViewById<ImageButton>(R.id.delKey).setOnClickListener { send(Keys.DEL) }
        findViewById<ImageButton>(R.id.enterKey).setOnClickListener { send(Keys.ENTER) }
        findViewById<ImageButton>(R.id.closeType).setOnClickListener { closeKeyboard() }
        typeField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
        }
    }

    private fun openKeyboard() {
        typePanel.visibility = View.VISIBLE
        typeField.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(typeField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeKeyboard() {
        hideKeyboard()
        typePanel.visibility = View.GONE
    }

    private fun submitTyped() {
        val text = typeField.text.toString()
        if (text.isBlank()) { send(Keys.ENTER); return }
        sendTextToTv(text)
        typeField.text.clear()
    }

    /**
     * Si el TV tiene un campo de texto enfocado, el texto entra directo (con tildes, ñ y emojis).
     * Si no, abre la búsqueda del TV y escribe ahí.
     */
    private fun sendTextToTv(text: String) {
        val s = session ?: return connect()
        if (s.imeActive) {
            s.text(text)
        } else {
            s.key(Keys.SEARCH)
            main.postDelayed({ session?.text(text) }, 1_200)
        }
    }

    // ======================================================================
    // Utilidades
    // ======================================================================

    private enum class Status { OK, CONNECTING, OFFLINE }

    private fun setStatus(status: Status, text: String) {
        val color = when (status) {
            Status.OK -> getColor(R.color.ok)
            Status.CONNECTING -> getColor(R.color.warn)
            Status.OFFLINE -> getColor(R.color.err)
        }
        statusDot.background.mutate().setTint(color)
        statusText.text = if (status == Status.OK && volumeText.isNotEmpty()) "$text · $volumeText" else text
    }

    /** Muestra un resumen corto; el detalle completo (cadena de causas) se ve tocando el estado. */
    private fun fail(summary: String, error: Throwable?) {
        lastError = error
        android.util.Log.w("TvRemote", summary, error)
        setStatus(Status.OFFLINE, "$summary · toca para ver detalle")
    }

    private fun showErrorDetail() {
        val chain = generateSequence(lastError) { it.cause }.take(5)
            .joinToString("\n\n") { "${it.javaClass.simpleName}: ${it.message}" }
        AlertDialog.Builder(this)
            .setTitle("Detalle del error")
            .setMessage(chain + "\n\nSi persiste: en el TV ve a Ajustes → Apps → Apps del sistema → " +
                "Android TV Remote Service → Borrar datos, y vuelve a emparejar.")
            .setPositiveButton("Reintentar") { _, _ -> lastError = null; retries = 0; connect() }
            .setNeutralButton("Nueva identidad") { _, _ ->
                // Regenera la llave del celular: útil si el TV quedó con un emparejamiento a medias
                IdentityStore.reset(this)
                prefs.edit().remove(K_PIN).apply()
                lastError = null
                recreate()
            }
            .setNegativeButton(R.string.change_tv) { _, _ -> confirmChangeTv() }
            .show()
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val K_HOST = "host"
        private const val K_NAME = "name"
        private const val K_PIN = "server_cert_sha256"
        private const val REQ_MIC = 1
        private const val REQ_DICTATE = 2
        private const val DICTATE_TO_TV = 0
        private const val DICTATE_TO_FIELD = 1

        /** Package name → se abre con market://launch; Int → tecla. */
        private val SHORTCUTS: List<Pair<String, Any>> = listOf(
            "Netflix" to "com.netflix.ninja",
            "YouTube" to "com.google.android.youtube.tv",
            "Disney+" to "com.disney.disneyplus",
            "Spotify" to "com.spotify.tv.android",
            "Entrada" to Keys.TV_INPUT,
            "Ajustes" to Keys.SETTINGS,
        )
    }
}
