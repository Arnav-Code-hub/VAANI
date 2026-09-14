package com.bithead.shelter.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Crypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    // ---- Android Keystore-backed master key --------------------------------
    // The AES key never leaves the secure hardware/TEE keystore and is never
    // stored in SharedPreferences, files, or app memory as raw bytes. This
    // replaces the previous "key in SharedPreferences" approach.
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "shelter_master_key"

    /**
     * Returns the app's AES-256-GCM master key from the Android Keystore,
     * generating it on first run. The key material itself is non-exportable;
     * only a handle (SecretKey wrapper) is returned.
     */
    fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun encrypt(input: File, output: File, key: SecretKey) {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintext = input.readBytes()
        val ciphertext = cipher.doFinal(plaintext)
        FileOutputStream(output).use { stream ->
            stream.write(iv)
            stream.write(ciphertext)
            stream.fd.sync()
        }
    }

    fun decrypt(input: File, output: File, key: SecretKey) {
        val bytes = input.readBytes()
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        output.writeBytes(plaintext)
    }


    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun chainHash(currentHash: String, previousHash: String?): String {
        val value = (previousHash.orEmpty() + currentHash).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }

    /**
     * NOTE: Keystore-backed keys (see getOrCreateKeystoreKey) are intentionally
     * non-exportable — key.encoded returns null/throws for them. This helper is
     * only meaningful for the legacy newKey()/keyFromBytes() path and should not
     * be used to display or log the production master key.
     */
    fun keyForDebugUi(key: SecretKey): String =
        key.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "(non-exportable Keystore key)"
}
