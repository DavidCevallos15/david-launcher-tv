package dev.davidcevallos.tvremote.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** Identidad TLS del cliente. En Android vive en AndroidKeyStore; en tests, en un PKCS12. */
interface TlsIdentity {
    val keyManager: X509KeyManager
    val publicKey: RSAPublicKey
}

/**
 * El TV usa un certificado autofirmado, así que la confianza no viene de una CA:
 *  - En el pairing, el PIN amarra criptográficamente ambos certificados (anti-MITM).
 *  - Después, se fija (pin) el SHA-256 del cert del TV visto en el pairing (TOFU).
 */
class PinningTrustManager(private val expectedSha256: String?) : X509TrustManager {
    var peer: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val cert = chain.first()
        if (expectedSha256 != null && sha256Hex(cert.encoded) != expectedSha256) {
            throw java.security.cert.CertificateException("El certificado del TV cambió: vuelve a emparejar")
        }
        peer = cert
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Conexión TLS con framing "varint length + protobuf". */
class FramedTlsConnection private constructor(
    private val socket: SSLSocket,
    val serverCert: X509Certificate,
) : Closeable {
    private val input: InputStream = BufferedInputStream(socket.inputStream)
    private val output: OutputStream = socket.outputStream

    fun send(message: ByteArray) {
        val frame = ProtoWriter.encodeVarint(message.size.toLong()) + message
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
    }

    /** Bloquea hasta recibir un mensaje completo. Lanza EOFException si el TV cerró. */
    fun receive(): ProtoMessage {
        val len = ProtoWriter.readVarint(input) ?: throw EOFException("Conexión cerrada por el TV")
        require(len in 0..MAX_FRAME) { "Frame inválido: $len" }
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException("Frame truncado")
            read += n
        }
        return ProtoMessage.parse(buf)
    }

    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        private const val MAX_FRAME = 512L * 1024
        const val CONNECT_TIMEOUT_MS = 5_000

        fun open(host: String, port: Int, identity: TlsIdentity, pinnedSha256: String?): FramedTlsConnection {
            val trust = PinningTrustManager(pinnedSha256)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(arrayOf(identity.keyManager), arrayOf(trust), null)
            val raw = java.net.Socket()
            raw.tcpNoDelay = true // cada tecla es un paquete chico: sin Nagle
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val socket = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.soTimeout = 10_000
            socket.startHandshake()
            return FramedTlsConnection(socket, trust.peer ?: error("Sin certificado del TV"))
        }
    }
}

object PairingMath {
    /**
     * secret = SHA-256(mod_cliente ‖ exp_cliente ‖ mod_tv ‖ exp_tv ‖ bytes(pin[2..6]))
     * El primer byte del hash debe coincidir con pin[0..2]: así se valida el PIN localmente
     * antes de enviarlo, y un MITM (con otro certificado) produce un hash distinto.
     */
    fun secret(client: RSAPublicKey, server: RSAPublicKey, pin: String): ByteArray? {
        val code = pin.trim().uppercase()
        require(code.length == 6 && code.all { it in "0123456789ABCDEF" }) { "El PIN son 6 caracteres hex" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(client.modulus))
        md.update(unsigned(client.publicExponent))
        md.update(unsigned(server.modulus))
        md.update(unsigned(server.publicExponent))
        md.update(hex(code.substring(2)))
        val hash = md.digest()
        return if ((hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) hash else null
    }

    /** Big-endian sin el byte de signo que agrega BigInteger.toByteArray(). */
    internal fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
