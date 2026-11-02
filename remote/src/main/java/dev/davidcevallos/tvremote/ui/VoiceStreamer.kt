package dev.davidcevallos.tvremote.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.davidcevallos.tvremote.core.RemoteSession

/**
 * Micrófono del celular → búsqueda por voz del TV, igual que el botón de mic del control físico.
 * El TV espera PCM 16-bit mono a 8 kHz; el reconocimiento lo hace el propio TV (Google, en su idioma).
 */
class VoiceStreamer(private val session: RemoteSession, private val sessionId: Int) {

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // el permiso se verifica antes de construir esta clase
    fun start() {
        running = true
        thread = Thread({
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2),
            )
            val buf = ByteArray(CHUNK)
            val deadline = System.currentTimeMillis() + MAX_MS
            try {
                rec.startRecording()
                while (running && System.currentTimeMillis() < deadline) {
                    var filled = 0
                    while (filled < CHUNK && running) {
                        val n = rec.read(buf, filled, CHUNK - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled > 0) session.voiceChunk(sessionId, buf.copyOf(filled))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                session.endVoice(sessionId)
            }
        }, "voice").apply { start() }
    }

    fun stop() { running = false }

    companion object {
        private const val RATE = 8_000
        private const val CHUNK = 8 * 1024   // ≈ 0.5 s de audio por mensaje: mínimo que acepta el TV
        private const val MAX_MS = 15_000L   // corte de seguridad si el dedo se queda pegado
    }
}
