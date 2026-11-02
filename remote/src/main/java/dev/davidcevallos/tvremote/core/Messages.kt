package dev.davidcevallos.tvremote.core

/**
 * Números de campo de polo.proto (pairing, puerto 6467) y remotemessage.proto (control, puerto 6466).
 * Verificados contra los descriptores protobuf de la implementación de referencia androidtvremote2.
 */
object Polo {
    const val PROTOCOL_VERSION = 1
    const val STATUS = 2
    const val PAIRING_REQUEST = 10
    const val PAIRING_REQUEST_ACK = 11
    const val OPTIONS = 20
    const val CONFIGURATION = 30
    const val CONFIGURATION_ACK = 31
    const val SECRET = 40
    const val SECRET_ACK = 41

    const val STATUS_OK = 200
    private const val ROLE_INPUT = 1
    private const val ENCODING_HEX = 3

    private fun outer(build: ProtoWriter.() -> Unit) = ProtoWriter()
        .int(PROTOCOL_VERSION, 2)
        .int(STATUS, STATUS_OK)
        .apply(build)
        .toByteArray()

    fun pairingRequest(clientName: String) = outer {
        message(PAIRING_REQUEST) {
            string(1, "atvremote") // service_name
            string(2, clientName)  // client_name: lo que muestra el TV
        }
    }

    fun options() = outer {
        message(OPTIONS) {
            message(1) { int(1, ENCODING_HEX); int(2, 6) } // input_encodings
            int(3, ROLE_INPUT)                              // preferred_role
        }
    }

    fun configuration() = outer {
        message(CONFIGURATION) {
            message(1) { int(1, ENCODING_HEX); int(2, 6) } // encoding
            int(2, ROLE_INPUT)                              // client_role
        }
    }

    fun secret(secret: ByteArray) = outer { message(SECRET) { bytes(1, secret) } }
}

object Remote {
    // RemoteMessage
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val ERROR = 3
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    const val KEY_INJECT = 10
    const val IME_KEY_INJECT = 20
    const val IME_BATCH_EDIT = 21
    const val IME_SHOW_REQUEST = 22
    const val VOICE_BEGIN = 30
    const val VOICE_PAYLOAD = 31
    const val VOICE_END = 32
    const val START = 40
    const val SET_VOLUME_LEVEL = 50
    const val APP_LINK_LAUNCH = 90

    // Features (bitmask de RemoteConfigure.code1 / RemoteSetActive.active)
    const val F_PING = 1
    const val F_KEY = 1 shl 1
    const val F_IME = 1 shl 2
    const val F_VOICE = 1 shl 3
    const val F_POWER = 1 shl 5
    const val F_VOLUME = 1 shl 6
    const val F_APP_LINK = 1 shl 9
    const val WANTED_FEATURES = F_PING or F_KEY or F_IME or F_VOICE or F_POWER or F_VOLUME or F_APP_LINK

    // RemoteDirection
    const val SHORT = 3
    const val START_LONG = 1
    const val END_LONG = 2

    fun configure(features: Int, model: String, vendor: String) = ProtoWriter()
        .message(CONFIGURE) {
            int(1, features)
            message(2) {
                string(1, model)
                string(2, vendor)
                int(3, 1)
                string(4, "1")
                string(5, "atvremote")
                string(6, "1.0.0")
            }
        }.toByteArray()

    fun setActive(features: Int) = ProtoWriter().message(SET_ACTIVE) { int(1, features) }.toByteArray()

    fun pingResponse(val1: Int) = ProtoWriter().message(PING_RESPONSE) { int(1, val1) }.toByteArray()

    fun key(keyCode: Int, direction: Int = SHORT) = ProtoWriter()
        .message(KEY_INJECT) { int(1, keyCode); int(2, direction) }
        .toByteArray()

    /** Texto vía IME (soporta Unicode: tildes, ñ, emojis). Mismo formato que la app oficial. */
    fun text(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        val cursor = text.length - 1
        return ProtoWriter().message(IME_BATCH_EDIT) {
            int(1, imeCounter)
            int(2, fieldCounter)
            message(3) {                  // edit_info (repeated, uno solo)
                int(1, 1)                 // insert
                message(2) {              // RemoteImeObject
                    int(1, cursor)
                    int(2, cursor)
                    string(3, text)
                }
            }
        }.toByteArray()
    }

    fun appLink(link: String) = ProtoWriter().message(APP_LINK_LAUNCH) { string(1, link) }.toByteArray()

    fun voiceBegin(sessionId: Int) = ProtoWriter().message(VOICE_BEGIN) { int(1, sessionId) }.toByteArray()

    fun voicePayload(sessionId: Int, samples: ByteArray) = ProtoWriter()
        .message(VOICE_PAYLOAD) { int(1, sessionId); bytes(2, samples) }
        .toByteArray()

    fun voiceEnd(sessionId: Int) = ProtoWriter().message(VOICE_END) { int(1, sessionId) }.toByteArray()
}

/** Keycodes de Android (RemoteKeyCode usa los mismos valores que android.view.KeyEvent). */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val VOLUME_MUTE = 164
    const val SETTINGS = 176
    const val TV_INPUT = 178
}
