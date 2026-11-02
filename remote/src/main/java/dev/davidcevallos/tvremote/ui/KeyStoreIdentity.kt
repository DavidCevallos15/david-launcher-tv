package dev.davidcevallos.tvremote.ui

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.davidcevallos.tvremote.core.TlsIdentity
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Identidad del celular: RSA-2048 generada dentro del AndroidKeyStore (respaldada por hardware
 * cuando el equipo lo soporta). La llave privada nunca sale del Keystore ni existe como archivo;
 * Conscrypt firma el handshake TLS a través de ella.
 */
class KeyStoreIdentity private constructor(private val ks: KeyStore) : TlsIdentity {

    override val publicKey: RSAPublicKey
        get() = ks.getCertificate(ALIAS).publicKey as RSAPublicKey

    override val keyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = ALIAS
        override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: SSLEngine?) = ALIAS
        override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
        override fun getPrivateKey(alias: String?) = ks.getKey(ALIAS, null) as PrivateKey
        override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?): String? = null
    }

    companion object {
        private const val ALIAS = "tv_remote_identity"

        fun loadOrCreate(): KeyStoreIdentity {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val start = Date()
                val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }.time
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setKeySize(2048)
                            // TLS 1.2 (PKCS#1) y TLS 1.3 (PSS); NONE para suites antiguas
                            .setDigests(
                                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
                            )
                            .setSignaturePaddings(
                                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                            )
                            .setCertificateSubject(X500Principal("CN=atvremote"))
                            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                            .setCertificateNotBefore(start)
                            .setCertificateNotAfter(end)
                            .build(),
                    )
                    generateKeyPair()
                }
            }
            return KeyStoreIdentity(ks)
        }
    }
}
