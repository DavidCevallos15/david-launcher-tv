package dev.davidcevallos.tvremote.core

import java.io.Closeable
import java.util.concurrent.Executors
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
        fun onDisconnected(error: Throwable?) {}
        /** Una línea por mensaje (salvo ping/pong y audio) para el panel de diagnóstico. */
        fun onLog(line: String) {}
    }

    enum class WriteMode { AUTO, IME, KEYS }

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-writer").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)

    @Volatile var features = 0; private set
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile var imeActive = false; private set
    /** Texto actual del campo enfocado en el TV, según lo último que el TV reportó. */
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
            ime.message(1)?.let { app ->
                if (app.has(1)) imeCounter = app.int(1) // app_info.counter = contador IME vigente
                app.string(12).takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
            }
            ime.message(2)?.let { field -> fieldCounter = field.int(1); fieldText = field.string(2); setIme(true) }
            return
        }
        msg.message(Remote.IME_BATCH_EDIT)?.let { edit ->
            imeCounter = edit.int(1); fieldCounter = edit.int(2)
            edit.message(3)?.message(2)?.let { fieldText = it.string(3) }
            setIme(true); return
        }
        msg.message(Remote.IME_SHOW_REQUEST)?.let { req ->
            val status = req.message(2)
            if (status != null) { fieldCounter = status.int(1); fieldText = status.string(2) }
            setIme(status != null); return
        }
        msg.message(Remote.VOICE_BEGIN)?.let {
            voiceCallback?.invoke(it.int(1)); voiceCallback = null; return
        }
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
        val useIme = when (mode) {
            WriteMode.IME -> true
            WriteMode.KEYS -> false
            WriteMode.AUTO -> imeActive && features and Remote.F_IME != 0
        }
        if (useIme) {
            val full = fieldText + value
            fieldText = full
            sendLogged(Remote.text(full, imeCounter, fieldCounter))
            return TypeMode.IME
        }
        val codes = TextKeys.toKeyCodes(value)
        listener.onLog("→ ${codes.size} teclas para \"$value\"")
        codes.forEach { key(it) }
        return TypeMode.KEYS
    }

    /** Reemplaza el texto del campo del TV (IME). */
    fun text(value: String) {
        if (value.isNotEmpty()) { fieldText = value; sendLogged(Remote.text(value, imeCounter, fieldCounter)) }
    }

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
