package dev.davidcevallos.tvremote.core

import java.io.Closeable
import java.security.interfaces.RSAPublicKey

class PairingException(message: String) : Exception(message)

/**
 * Emparejamiento (puerto 6467). Bloqueante: llamar desde un hilo de fondo.
 *
 *   cliente → pairing_request      TV → pairing_request_ack
 *   cliente → options              TV → options
 *   cliente → configuration        TV → configuration_ack   (aquí el TV muestra el PIN)
 *   cliente → secret               TV → secret_ack
 */
class PairingSession private constructor(
    private val conn: FramedTlsConnection,
    private val identity: TlsIdentity,
) : Closeable {

    /** SHA-256 del certificado del TV: se guarda para fijarlo (pinning) en las conexiones siguientes. */
    val serverCertSha256: String = sha256Hex(conn.serverCert.encoded)

    private fun exchange(message: ByteArray, expectField: Int) {
        conn.send(message)
        val reply = conn.receive()
        val status = reply.int(Polo.STATUS)
        if (status != Polo.STATUS_OK) throw PairingException("El TV respondió con estado $status")
        if (!reply.has(expectField)) throw PairingException("Respuesta inesperada del TV")
    }

    /** Hasta que el TV muestra el PIN en pantalla. */
    private fun begin(clientName: String) {
        exchange(Polo.pairingRequest(clientName), Polo.PAIRING_REQUEST_ACK)
        exchange(Polo.options(), Polo.OPTIONS)
        exchange(Polo.configuration(), Polo.CONFIGURATION_ACK)
    }

    /**
     * @return true si el TV aceptó el PIN.
     * Un PIN que no cuadra con el hash se detecta localmente y no se envía (el TV mantiene el PIN en pantalla).
     */
    fun finish(pin: String): Boolean {
        val server = conn.serverCert.publicKey as? RSAPublicKey ?: throw PairingException("El TV no usa RSA")
        val secret = PairingMath.secret(identity.publicKey, server, pin) ?: return false
        conn.setReadTimeout(10_000)
        exchange(Polo.secret(secret), Polo.SECRET_ACK)
        return true
    }

    override fun close() = conn.close()

    companion object {
        const val PORT = 6467

        fun start(host: String, identity: TlsIdentity, clientName: String, port: Int = PORT): PairingSession {
            val conn = FramedTlsConnection.open(host, port, identity, pinnedSha256 = null)
            return try {
                PairingSession(conn, identity).also { it.begin(clientName) }
            } catch (e: Exception) {
                conn.close()
                throw e
            }
        }
    }
}
