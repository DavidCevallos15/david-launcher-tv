package dev.davidcevallos.tvremote.core

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sesión de control (puerto 6466). Un hilo lector responde configure/set_active/ping y
 * actualiza estado; los envíos van por un ejecutor de un solo hilo para no bloquear la UI.
 */
class RemoteSession private constructor(
    private val conn: FramedTlsConnection,
    private val listener: Listener,
    private val model: String,
    private val vendor: String,
) : Closeable {

    interface Listener {
        fun onReady(tvModel: String, features: Int) {}
        fun onPower(isOn: Boolean) {}
        fun onVolume(level: Int, max: Int, muted: Boolean) {}
        fun onCurrentApp(packageName: String) {}
        fun onImeField(active: Boolean) {}
        /** El TV reportó el texto de su campo enfocado (al enfocarlo o tras una edición). */
        fun onFieldText(text: String) {}
        /** Hay texto esperando: el TV todavía no tiene un campo con su teclado abierto. */
        fun onTextQueued() {}
        fun onDisconnected(error: Throwable?) {}
        /** Una línea por mensaje (salvo ping/pong y audio) para el panel de diagnóstico. */
        fun onLog(line: String) {}
    }

    enum class WriteMode { AUTO, IME, KEYS }

    private val writer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-writer").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)

    @Volatile var features = 0; private set

    /*
     * Modelo IME medido en el TCL BeyondTV2 (Android TV 11, Remote Service 7.00):
     *  - ime_counter  = ime_key_inject.app_info.counter (p. ej. 123). El ime_batch_edit {ime_counter: 1}
     *    que llega al lado es un señuelo: usarlo → "Ignoring edit, the input field has changed from 1 to 123".
     *  - field_counter = text_field_status.counter_field; SUBE con cada edición y el TV confirma el nuevo
     *    en ime_show_request (~10 ms). Una edición con un contador viejo se rechaza o se re-basa (duplica).
     *  - edit_info.start/end = rango a reemplazar (bytes UTF-8, el TV lo recorta): 0..fin = reemplazar todo.
     *  - Sin teclado abierto en el TV solo llega ime_key_inject {app_info {app_package}}: no hay campo.
     * Por eso: una edición en vuelo a la vez, se espera el eco y se envía solo el último texto pedido.
     */
    private val imeLock = Any()
    private var imeCounter = NONE
    private var fieldCounter = NONE
    /** Texto del campo según el último reporte del TV. */
    private var tvText = ""
    /** Último texto pedido que aún no se envió (null = nada pendiente). */
    private var desired: String? = null
    private var desiredAt = 0L
    private var inFlight = false
    private var editSeq = 0
    private var retried = false

    @Volatile var imeActive = false; private set
    /** Texto del campo del TV: lo que el usuario pidió, o lo último que reportó el TV. */
    @Volatile var fieldText = ""; private set
    @Volatile var tvFeatures = 0; private set
    @Volatile private var pendingLinks: List<String> = emptyList()
    @Volatile private var voiceCallback: ((Int?) -> Unit)? = null

    val voiceSupported get() = features and Remote.F_VOICE != 0

    private val reader = Thread({ readLoop() }, "tv-reader").apply { isDaemon = true }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            conn.setReadTimeout(16_000) // el TV hace ping cada ~5 s; 3 sin respuesta = muerto
            while (!closed.get()) handle(conn.receive())
        } catch (e: Throwable) {
            if (!closed.get()) error = e
        } finally {
            closeInternal()
            listener.onDisconnected(error)
        }
    }

    private fun handle(msg: ProtoMessage) {
        if (!msg.has(Remote.PING_REQUEST)) listener.onLog("← " + Remote.describe(msg))
        msg.message(Remote.ERROR)?.let {
            // Si un app link falló, probar el siguiente formato
            val next = pendingLinks.firstOrNull()
            if (next != null) { pendingLinks = pendingLinks.drop(1); sendLogged(Remote.appLink(next)) }
            return
        }
        msg.message(Remote.CONFIGURE)?.let { cfg ->
            tvFeatures = cfg.int(1)
            features = tvFeatures and Remote.WANTED_FEATURES
            val tvModel = cfg.message(2)?.string(1).orEmpty()
            listener.onLog("TV anuncia: ${Remote.featureNames(tvFeatures)} (code1=$tvFeatures) → activo: ${Remote.featureNames(features)}")
            sendNow(Remote.configure(features, model, vendor))
            listener.onReady(tvModel, features)
            return
        }
        if (msg.has(Remote.SET_ACTIVE)) { sendNow(Remote.setActive(features)); return }
        msg.message(Remote.PING_REQUEST)?.let { sendNow(Remote.pingResponse(it.int(1))); return }
        msg.message(Remote.START)?.let { listener.onPower(it.bool(1)); return }
        msg.message(Remote.SET_VOLUME_LEVEL)?.let {
            listener.onVolume(it.int(7), it.int(6), it.bool(8)); return
        }
        msg.message(Remote.IME_KEY_INJECT)?.let { ime ->
            val app = ime.message(1)
            val field = ime.message(2)
            app?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
            val hasField = app?.has(1) == true || field != null
            synchronized(imeLock) {
                if (!hasField) {
                    // Teclado del TV cerrado: no hay a dónde escribir
                    imeCounter = NONE; fieldCounter = NONE; inFlight = false
                } else {
                    if (app?.has(1) == true) imeCounter = app.int(1)
                    if (field != null && field.has(1)) { fieldCounter = field.int(1); tvText = field.string(2) }
                }
            }
            if (!hasField) { setIme(false); return }
            if (field != null) reportText(field.string(2))
            setIme(true); pump(); return
        }
        msg.message(Remote.IME_BATCH_EDIT)?.let { return } // señuelo del TV (ver arriba): no trae el campo vigente
        msg.message(Remote.IME_SHOW_REQUEST)?.let { req ->
            val status = req.message(2) ?: return
            val text = status.string(2)
            synchronized(imeLock) {
                if (status.has(1)) fieldCounter = status.int(1)
                tvText = text
                inFlight = false // eco de nuestra edición (o el TV cambió el campo)
            }
            reportText(text)
            if (hasTvField) setIme(true)
            pump(); return
        }
        msg.message(Remote.VOICE_BEGIN)?.let {
            voiceCallback?.invoke(it.int(1)); voiceCallback = null; return
        }
    }

    private fun reportText(text: String) {
        if (synchronized(imeLock) { desired == null }) fieldText = text
        listener.onFieldText(text)
    }

    /** Envía el texto pendiente si hay campo y nada en vuelo. Una sola edición a la vez. */
    private fun pump() {
        val edit = synchronized(imeLock) {
            val want = desired ?: return
            if (System.currentTimeMillis() - desiredAt > QUEUE_TTL_MS) { desired = null; return } // ya no aplica
            if (inFlight || imeCounter == NONE || fieldCounter == NONE) return
            desired = null
            if (want == tvText) return
            inFlight = true
            val seq = ++editSeq
            val bytes = Remote.text(want, imeCounter, fieldCounter, utf8Len(tvText))
            writer.schedule({ onEditTimeout(seq, want) }, ECHO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bytes
        }
        sendLogged(edit)
    }

    /** Sin eco: el campo cambió o el TV la descartó. Un reintento con los contadores actuales. */
    private fun onEditTimeout(seq: Int, text: String) {
        synchronized(imeLock) {
            if (!inFlight || seq != editSeq) return
            inFlight = false
            if (retried) { retried = false; listener.onLog("⚠ el TV no confirmó el texto"); return }
            retried = true
            if (desired == null) { desired = text; desiredAt = System.currentTimeMillis() }
        }
        pump()
    }

    private fun setIme(active: Boolean) {
        if (imeActive != active) { imeActive = active; listener.onImeField(active) }
    }

    // ---------- API pública (no bloqueante) ----------

    fun key(code: Int, direction: Int = Remote.SHORT) = post(Remote.key(code, direction))

    enum class TypeMode { IME, KEYS }

    /**
     * Escribe en el TV.
     *  - IME: el TV recibe el texto completo del campo (lo que ya había + lo nuevo), con tildes/ñ/emojis.
     *    Requiere que el teclado del TV sea compatible (Gboard para TV).
     *  - KEYS: tecla por tecla, sin tildes; sirve con cualquier teclado.
     *  - AUTO: IME si el TV anunció su campo, si no KEYS.
     */
    fun type(value: String, mode: WriteMode = WriteMode.AUTO): TypeMode {
        if (usesIme(mode)) {
            setText(join(fieldText, value))
            return TypeMode.IME
        }
        val codes = TextKeys.toKeyCodes(value)
        listener.onLog("→ ${codes.size} teclas para \"$value\"")
        codes.forEach { key(it) }
        return TypeMode.KEYS
    }

    /** IME si el TV lo soporta (o si se fuerza); teclas solo para TVs sin IME. */
    fun usesIme(mode: WriteMode = WriteMode.AUTO) = when (mode) {
        WriteMode.IME -> true
        WriteMode.KEYS -> false
        WriteMode.AUTO -> features and Remote.F_IME != 0
    }

    /**
     * Deja el campo del TV exactamente con [full] (espejo del campo del celular): escribir, corregir
     * y borrar en el celular se refleja igual en el TV. Vacío = borrar todo. Se puede llamar en cada
     * tecla: si hay una edición en vuelo, solo se guarda el último texto y sale al llegar el eco.
     * Si el TV aún no tiene un campo con teclado, el texto espera (hasta 30 s) y sale al abrirse.
     */
    fun setText(full: String) {
        fieldText = full
        val waiting = synchronized(imeLock) {
            desired = full; desiredAt = System.currentTimeMillis(); retried = false
            imeCounter == NONE || fieldCounter == NONE
        }
        if (waiting) { listener.onLog("⏳ texto en espera: el TV no tiene un campo abierto"); listener.onTextQueued() }
        pump()
    }

    /** true si el TV tiene ahora un campo con su teclado abierto. */
    val hasTvField get() = synchronized(imeLock) { imeCounter != NONE && fieldCounter != NONE }

    /** Compatibilidad: reemplaza el texto del campo. */
    fun text(value: String) = setText(value)


    /**
     * Abre una app. Prueba los formatos en orden: si el TV responde con error, envía el siguiente.
     * Package name → market://launch?id=…; también acepta deep links (https://…).
     */
    fun launch(vararg appIdsOrLinks: String) {
        val links = appIdsOrLinks.map { if ("://" in it) it else "market://launch?id=$it" }
        if (links.isEmpty()) return
        if (features and Remote.F_APP_LINK == 0) listener.onLog("⚠ el TV no activó APP_LINK: puede ignorar el app link")
        pendingLinks = links.drop(1)
        sendLogged(Remote.appLink(links.first()))
    }

    private fun sendLogged(bytes: ByteArray) {
        listener.onLog("→ " + Remote.describe(bytes))
        post(bytes)
    }

    /**
     * Inicia una sesión de voz: presiona SEARCH y espera voice_begin del TV (≤ 2 s).
     * [onReady] recibe el sessionId, o null si el TV no abrió sesión.
     */
    fun startVoice(onReady: (Int?) -> Unit) {
        if (!voiceSupported) { onReady(null); return }
        voiceCallback = { id ->
            if (id != null) sendNow(Remote.voiceBegin(id))
            onReady(id)
        }
        key(Keys.SEARCH)
        Thread {
            Thread.sleep(2_000)
            voiceCallback?.let { voiceCallback = null; it(null) }
        }.apply { isDaemon = true }.start()
    }

    /** PCM 16-bit mono 8 kHz. El TV exige trozos de 8–20 KB. */
    fun voiceChunk(sessionId: Int, pcm: ByteArray) {
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + VOICE_MAX, pcm.size)
            var part = pcm.copyOfRange(i, end)
            if (part.size < VOICE_MIN) part = part.copyOf(VOICE_MIN)
            post(Remote.voicePayload(sessionId, part))
            i = end
        }
    }

    fun endVoice(sessionId: Int) = post(Remote.voiceEnd(sessionId))

    private fun post(bytes: ByteArray) {
        if (closed.get()) return
        writer.execute { sendNow(bytes) }
    }

    private fun sendNow(bytes: ByteArray) {
        try { conn.send(bytes) } catch (e: Exception) { closeInternal() }
    }

    private fun closeInternal() {
        if (closed.compareAndSet(false, true)) {
            conn.close()
            writer.shutdown()
        }
    }

    override fun close() {
        closeInternal()
        writer.awaitTermination(500, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** Une dos fragmentos con un solo espacio ("hola" + "mundo" → "hola mundo"). */
        fun join(current: String, add: String): String = when {
            current.isBlank() -> add.trim()
            add.isBlank() -> current
            else -> current.trimEnd() + " " + add.trim()
        }

        private fun utf8Len(s: String) = s.toByteArray(Charsets.UTF_8).size

        private const val NONE = Int.MIN_VALUE
        private const val ECHO_TIMEOUT_MS = 700L
        private const val QUEUE_TTL_MS = 30_000L
        const val PORT = 6466
        private const val VOICE_MIN = 8 * 1024
        private const val VOICE_MAX = 20 * 1024

        /**
         * Lanza SSLException si el TV no reconoce este celular (hay que emparejar)
         * o si su certificado no coincide con el fijado.
         */
        fun connect(
            host: String,
            identity: TlsIdentity,
            pinnedSha256: String?,
            listener: Listener,
            model: String = "TV Remote",
            vendor: String = "David Launcher TV",
            port: Int = PORT,
        ): RemoteSession {
            val conn = FramedTlsConnection.open(host, port, identity, pinnedSha256)
            return RemoteSession(conn, listener, model, vendor).also { it.reader.start() }
        }
    }
}
