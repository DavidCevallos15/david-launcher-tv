package dev.davidcevallos.tvremote.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.davidcevallos.tvremote.core.SoftwareIdentity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persiste la identidad TLS cifrada con AES-256-GCM. La llave AES vive en el AndroidKeyStore
 * (respaldada por hardware si el equipo lo soporta) y nunca sale de él; en disco solo queda
 * el blob cifrado, en noBackupFilesDir (no viaja en backups ni a la nube).
 *
 * La llave RSA de TLS opera en software porque algunos TV rechazan las firmas TLS 1.3
 * producidas por el Keystore; así se comporta igual que la app oficial.
 */
object IdentityStore {

    private const val FILE = "tv_identity.bin"
    private const val WRAP_ALIAS = "tv_remote_wrap"
    private const val IV_LEN = 12

    fun loadOrCreate(context: Context): SoftwareIdentity {
        val file = File(context.noBackupFilesDir, FILE)
        if (file.exists()) {
            runCatching { return SoftwareIdentity.decode(decrypt(file.readBytes())) }
            file.delete() // corrupto o la llave del Keystore se perdió: se regenera (requiere re-emparejar)
        }
        return SoftwareIdentity.generate().also { file.writeBytes(encrypt(it.encode())) }
    }

    /** Borra la identidad: el TV dejará de reconocer este celular. */
    fun reset(context: Context) {
        File(context.noBackupFilesDir, FILE).delete()
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
        }
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }
}
