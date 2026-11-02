package dev.davidcevallos.tvremote.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Identidad TLS con llave RSA en software, el mismo esquema que usan los clientes que el TV
 * acepta (app oficial, androidtvremote2). En Android se guarda cifrada con una llave del
 * AndroidKeyStore (ver IdentityStore); aquí solo vive la lógica pura, testeable en JVM.
 */
class SoftwareIdentity(private val key: PrivateKey, val certificate: X509Certificate) : TlsIdentity {

    override val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    override val keyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = ALIAS
        override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: SSLEngine?) = ALIAS
        override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
        override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?): String? = null
    }

    /** [len PKCS#8][PKCS#8][cert DER] — para persistir (cifrado) y restaurar. */
    fun encode(): ByteArray {
        val pkcs8 = key.encoded
        return ByteBuffer.allocate(4 + pkcs8.size + certificate.encoded.size)
            .putInt(pkcs8.size).put(pkcs8).put(certificate.encoded).array()
    }

    fun certificatePem(): String =
        "-----BEGIN CERTIFICATE-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
            "\n-----END CERTIFICATE-----\n"

    companion object {
        private const val ALIAS = "atvremote"

        fun generate(commonName: String = "atvremote"): SoftwareIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = SelfSignedCert.build(commonName, pair.public as RSAPublicKey, pair.private)
            return SoftwareIdentity(pair.private, cert)
        }

        fun decode(data: ByteArray): SoftwareIdentity {
            val buf = ByteBuffer.wrap(data)
            val pkcs8 = ByteArray(buf.int).also { buf.get(it) }
            val der = ByteArray(buf.remaining()).also { buf.get(it) }
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return SoftwareIdentity(key, cert)
        }
    }
}

/** Certificado X.509 v3 autofirmado (SHA256withRSA) codificado a mano en DER: sin BouncyCastle. */
object SelfSignedCert {

    fun build(commonName: String, publicKey: RSAPublicKey, privateKey: PrivateKey, years: Int = 20): X509Certificate {
        val now = System.currentTimeMillis()
        // Un día hacia atrás: tolera relojes desfasados entre celular y TV
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + years * 365L * 24 * 3600 * 1000)
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }

        val name = seq(set(seq(oid(OID_CN), utf8(commonName))))
        val tbs = seq(
            tlv(0xA0, int(BigInteger.valueOf(2))),          // version v3
            int(BigInteger(1, serial)),
            SHA256_RSA,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            publicKey.encoded,                             // SubjectPublicKeyInfo (ya en DER)
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(tbs); sign() }
        val der = seq(tbs, SHA256_RSA, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private val OID_CN = byteArrayOf(0x55, 0x04, 0x03)
    private val SHA256_RSA = seq(
        tlv(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
        byteArrayOf(0x05, 0x00),
    )

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun oid(v: ByteArray) = tlv(0x06, v)
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    /** UTCTime hasta 2049, GeneralizedTime desde 2050 (RFC 5280 §4.1.2.5). */
    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(d)
        return tlv(tag, text.toByteArray(Charsets.US_ASCII))
    }
}
