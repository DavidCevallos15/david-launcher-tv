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
    }

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-writer").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)

    @Volatile var features = 0; private set
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile var imeActive = false; private set
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
        msg.message(Remote.CONFIGURE)?.let { cfg ->
            features = cfg.int(1) and Remote.WANTED_FEATURES
            val tvModel = cfg.message(2)?.string(1).orEmpty()
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
            ime.message(1)?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
            ime.message(2)?.let { fieldCounter = it.int(1); setIme(true) }
            return
        }
        msg.message(Remote.IME_BATCH_EDIT)?.let {
            imeCounter = it.int(1); fieldCounter = it.int(2); setIme(true); return
        }
        msg.message(Remote.IME_SHOW_REQUEST)?.let { req ->
            val status = req.message(2)
            if (status != null) fieldCounter = status.int(1)
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

    fun text(value: String) {
        if (value.isNotEmpty()) post(Remote.text(value, imeCounter, fieldCounter))
    }

    /** Abre una app por package name (market://launch) o por deep link (https://…). */
    fun launch(appIdOrLink: String) {
        val link = if ("://" in appIdOrLink) appIdOrLink else "market://launch?id=$appIdOrLink"
        post(Remote.appLink(link))
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
