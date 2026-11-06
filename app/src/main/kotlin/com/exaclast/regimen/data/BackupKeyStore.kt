package com.exaclast.regimen.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.sync.BundleCrypto
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds this device's backup encryption key so backups can be written without asking for the
 * passphrase every time.
 *
 * The data key is stored encrypted by a non-exportable Android Keystore key, alongside the
 * passphrase envelope that goes into every file's header. The passphrase itself is never stored.
 * Its preferences file is excluded from Android's device backup: the Keystore key can't move to
 * another phone, so a copied blob would be useless there.
 */
class BackupKeyStore(context: Context) {

  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  val isConfigured: Boolean
    get() = envelope() != null && dataKey() != null

  fun envelope(): BundleCrypto.KeyEnvelope? {
    val raw = prefs.getString(KEY_ENVELOPE, null) ?: return null
    return runCatching { BackupFiles.json.decodeFromString(BundleCrypto.KeyEnvelope.serializer(), raw) }.getOrNull()
  }

  val keyId: String?
    get() = envelope()?.keyId

  /** The cached data key, or null if none (or the Keystore key was lost, e.g. after a restore). */
  @Synchronized
  fun dataKey(): ByteArray? {
    val iv = prefs.getString(KEY_WRAP_IV, null) ?: return null
    val wrapped = prefs.getString(KEY_WRAPPED_DATA_KEY, null) ?: return null
    return runCatching {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, keystoreKey(create = false) ?: return null, GCMParameterSpec(128, decode(iv)))
      cipher.doFinal(decode(wrapped))
    }.getOrNull()
  }

  @Synchronized
  fun save(dataKey: ByteArray, envelope: BundleCrypto.KeyEnvelope) {
    require(BundleCrypto.keyId(dataKey) == envelope.keyId)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
    val wrapped = cipher.doFinal(dataKey)
    prefs.edit()
      .putString(KEY_WRAP_IV, encode(cipher.iv))
      .putString(KEY_WRAPPED_DATA_KEY, encode(wrapped))
      .putString(KEY_ENVELOPE, BackupFiles.json.encodeToString(BundleCrypto.KeyEnvelope.serializer(), envelope))
      .commit()
  }

  @Synchronized
  fun clear() {
    prefs.edit().clear().commit()
    runCatching { androidKeyStore().deleteEntry(KEYSTORE_ALIAS) }
  }

  private fun keystoreKey(create: Boolean): SecretKey? {
    val store = androidKeyStore()
    (store.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    if (!create) return null
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
  private fun decode(text: String) = Base64.getDecoder().decode(text)

  companion object {
    const val PREFS_NAME = "regimen_backup_keys"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "regimen_backup_data_key_wrap"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ENVELOPE = "envelope"
    private const val KEY_WRAP_IV = "wrap_iv"
    private const val KEY_WRAPPED_DATA_KEY = "wrapped_data_key"
  }
}
