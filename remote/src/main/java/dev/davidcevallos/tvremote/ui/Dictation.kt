package dev.davidcevallos.tvremote.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Dictado en el celular con SpeechRecognizer (sin diálogo): pulsar para hablar, ver la
 * transcripción en vivo y entregar el texto final al soltar. El reconocimiento lo hace el
 * servicio de Google cuando está instalado: el reconocedor por defecto de algunos fabricantes
 * (p. ej. Samsung) ignora EXTRA_LANGUAGE y transcribe en inglés.
 */
class Dictation(
    context: Context,
    /** Etiqueta BCP-47 del idioma a reconocer (p. ej. "es-US"). Se lee en cada start(). */
    private val language: () -> String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val recognizer: SpeechRecognizer? = when {
        !SpeechRecognizer.isRecognitionAvailable(context) -> null
        else -> googleService(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: SpeechRecognizer.createSpeechRecognizer(context)
    }

    val available get() = recognizer != null
    private var lastPartial = ""
    private var delivered = false

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle) {
                b.best()?.let { lastPartial = it; onPartial(it) }
            }

            override fun onResults(b: Bundle) = deliver(b.best() ?: lastPartial)

            override fun onError(error: Int) {
                // Si ya había texto parcial (p. ej. soltó el botón muy pronto), se entrega igual.
                if (lastPartial.isNotBlank()) { deliver(lastPartial); return }
                if (delivered) return
                delivered = true
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché, intenta de nuevo"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El dictado necesita internet"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado, intenta de nuevo"
                        else -> "Error de dictado ($error)"
                    },
                )
            }

            override fun onReadyForSpeech(p: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rms: Float) = Unit
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(t: Int, p: Bundle?) = Unit
        })
    }

    private fun Bundle.best(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun deliver(text: String) {
        if (delivered) return
        delivered = true
        if (text.isNotBlank()) onFinal(text.trim()) else onError("No te escuché, intenta de nuevo")
    }

    fun start() {
        val r = recognizer ?: return onError("Este celular no tiene reconocimiento de voz")
        lastPartial = ""
        delivered = false
        r.cancel()
        val lang = language()
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                .putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Mientras mantiene presionado, no cortar por silencios cortos
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L),
        )
    }

    /** Termina de escuchar y produce el resultado final (onResults). */
    fun stop() = recognizer?.stopListening()

    fun destroy() = recognizer?.destroy()

    companion object {
        /** Idiomas del dictado: etiqueta para el reconocedor → nombre corto para la interfaz. */
        val LANGUAGES = linkedMapOf("es-US" to "ES", "en-US" to "EN")

        fun defaultLanguage(): String = if (Locale.getDefault().language == "en") "en-US" else "es-US"

        private val GOOGLE_PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

        /** Servicio de reconocimiento de Google, si está instalado (respeta el idioma pedido). */
        fun googleService(context: Context): ComponentName? {
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
            return GOOGLE_PACKAGES.firstNotNullOfOrNull { pkg ->
                services.firstOrNull { it.packageName == pkg }?.let { ComponentName(it.packageName, it.name) }
            }
        }
    }
}
