package dev.nish.nessa.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsDataStore by preferencesDataStore(name = "whisker_secrets")

/**
 * Stores [Secrets] encrypted with an AES-256-GCM key that lives in the Android Keystore
 * and never leaves the phone's secure hardware. Only the ciphertext is written to disk.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val store = context.applicationContext.secretsDataStore

    override suspend fun load(): Secrets? {
        val blob = store.data.first()[BLOB] ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            val plain = cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN).decodeToString()
            val s = Json.decodeFromString<Stored>(plain)
            Secrets(s.email, s.password, s.refreshToken)
        }.getOrNull() // unreadable (key lost, data damaged) is the same as not signed in
    }

    override suspend fun save(s: Secrets) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = Json.encodeToString(Stored.serializer(), Stored(s.email, s.password, s.refreshToken))
        val bytes = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        store.edit { it[BLOB] = Base64.encodeToString(bytes, Base64.NO_WRAP) }
    }

    override suspend fun clear() {
        store.edit { it.remove(BLOB) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    @Serializable
    private data class Stored(val email: String, val password: String, val refreshToken: String)

    private companion object {
        const val ALIAS = "nessa.whisker"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        val BLOB = stringPreferencesKey("blob")
    }
}
