package com.exaclast.regimen.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encrypted container for backups and sync files.
 *
 * Every file is encrypted with one random 256-bit data key (AES-256-GCM). The data key is stored in
 * the file's header wrapped by a key derived from the user's passphrase (PBKDF2-HMAC-SHA256), so any
 * single file can be restored on a new device with just the passphrase. The recovery code is the
 * data key itself (with a checksum), which also unlocks every file and can be shown as a QR code.
 * Changing the passphrase only re-wraps the data key.
 *
 * Layout: `MAGIC | u32 header length | header JSON | 12-byte nonce | ciphertext + GCM tag`.
 * The ciphertext is gzipped bundle JSON, and the magic, length and header are authenticated as
 * associated data, so tampering with any of them fails decryption.
 */
object BundleCrypto {

  class NotABackupException : Exception("This file isn't a Regimen backup")
  class UnsupportedFormatException(format: Int) : Exception("Backup format $format needs a newer version of Regimen")
  class WrongPassphraseException : Exception("Incorrect passphrase")
  class WrongKeyException : Exception("This recovery code is for a different backup")
  class CorruptedException : Exception("The backup is damaged and can't be read")
  class InvalidRecoveryCodeException : Exception("That recovery code isn't valid; check for typos")

  const val FORMAT_VERSION = 1
  /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
  const val DEFAULT_ITERATIONS = 600_000

  private val MAGIC = "RGMNBK".toByteArray(Charsets.US_ASCII)
  private const val KEY_BYTES = 32
  private const val NONCE_BYTES = 12
  private const val SALT_BYTES = 16
  private const val TAG_BITS = 128
  private const val CHECKSUM_BYTES = 2
  private val WRAP_AAD_PREFIX = "regimen-key-wrap-v1:".toByteArray(Charsets.US_ASCII)

  private val random = SecureRandom()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Serializable
  data class Kdf(
    val algorithm: String = "PBKDF2-HMAC-SHA256",
    val salt: String,
    val iterations: Int,
  )

  @Serializable
  data class WrappedKey(val nonce: String, val ciphertext: String)

  /** The data key locked with a passphrase. Stored in every file's header. */
  @Serializable
  data class KeyEnvelope(val keyId: String, val kdf: Kdf, val wrappedKey: WrappedKey)

  @Serializable
  data class Header(
    val format: Int = FORMAT_VERSION,
    val keyId: String,
    val envelope: KeyEnvelope? = null,
    val compression: String = "gzip",
    val createdAtEpochMillis: Long = 0L,
  )

  fun newDataKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

  /** Short public fingerprint of a data key: tells which key a file needs without revealing it. */
  fun keyId(dataKey: ByteArray): String =
    sha256(dataKey).copyOf(8).joinToString("") { "%02x".format(it) }

  fun wrapWithPassphrase(
    dataKey: ByteArray,
    passphrase: CharArray,
    iterations: Int = DEFAULT_ITERATIONS,
  ): KeyEnvelope {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val kek = deriveKey(passphrase, salt, iterations)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val id = keyId(dataKey)
    val wrapped = aesGcm(Cipher.ENCRYPT_MODE, kek, nonce, WRAP_AAD_PREFIX + id.toByteArray()).doFinal(dataKey)
    return KeyEnvelope(
      keyId = id,
      kdf = Kdf(salt = b64(salt), iterations = iterations),
      wrappedKey = WrappedKey(nonce = b64(nonce), ciphertext = b64(wrapped)),
    )
  }

  /** Unlocks the data key from [envelope]; throws [WrongPassphraseException]. */
  fun unwrapWithPassphrase(envelope: KeyEnvelope, passphrase: CharArray): ByteArray {
    val kek = deriveKey(passphrase, unb64(envelope.kdf.salt), envelope.kdf.iterations)
    val dataKey = try {
      aesGcm(Cipher.DECRYPT_MODE, kek, unb64(envelope.wrappedKey.nonce), WRAP_AAD_PREFIX + envelope.keyId.toByteArray())
        .doFinal(unb64(envelope.wrappedKey.ciphertext))
    } catch (e: AEADBadTagException) {
      throw WrongPassphraseException()
    }
    if (keyId(dataKey) != envelope.keyId) throw WrongPassphraseException()
    return dataKey
  }

  fun encrypt(
    plaintext: ByteArray,
    dataKey: ByteArray,
    envelope: KeyEnvelope?,
    nowMillis: Long = System.currentTimeMillis(),
  ): ByteArray {
    val header = Header(keyId = keyId(dataKey), envelope = envelope, createdAtEpochMillis = nowMillis)
    require(envelope == null || envelope.keyId == header.keyId) { "Envelope is for a different key" }
    val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
    val prefix = MAGIC + ByteBuffer.allocate(4).putInt(headerBytes.size).array() + headerBytes
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val ciphertext = aesGcm(Cipher.ENCRYPT_MODE, dataKey, nonce, prefix).doFinal(gzip(plaintext))
    return prefix + nonce + ciphertext
  }

  /** Reads the unencrypted header (e.g. to check which key or passphrase a file needs). */
  fun readHeader(file: ByteArray): Header = parse(file).header

  fun isEncryptedBackup(file: ByteArray): Boolean =
    file.size >= MAGIC.size && file.copyOf(MAGIC.size).contentEquals(MAGIC)

  /** Decrypts with a known data key (the device's cached key, or a parsed recovery code). */
  fun decrypt(file: ByteArray, dataKey: ByteArray): ByteArray {
    val parsed = parse(file)
    if (keyId(dataKey) != parsed.header.keyId) throw WrongKeyException()
    val plain = try {
      aesGcm(Cipher.DECRYPT_MODE, dataKey, parsed.nonce, parsed.prefix).doFinal(parsed.ciphertext)
    } catch (e: AEADBadTagException) {
      throw CorruptedException()
    }
    return try {
      gunzip(plain)
    } catch (e: java.io.IOException) {
      throw CorruptedException()
    }
  }

  /** Decrypts with the passphrase alone; returns the plaintext and the data key to cache. */
  fun decryptWithPassphrase(file: ByteArray, passphrase: CharArray): Pair<ByteArray, ByteArray> {
    val envelope = parse(file).header.envelope ?: throw WrongPassphraseException()
    val dataKey = unwrapWithPassphrase(envelope, passphrase)
    return decrypt(file, dataKey) to dataKey
  }

  // region Recovery code

  /**
   * The data key plus a 2-byte checksum in Crockford base32 (no I, L, O, U), in groups of five:
   * 55 characters, e.g. `7K3QF-…`. Unlocks every file written with that key.
   */
  fun recoveryCode(dataKey: ByteArray): String {
    require(dataKey.size == KEY_BYTES)
    val payload = dataKey + sha256(dataKey).copyOf(CHECKSUM_BYTES)
    return base32(payload).chunked(5).joinToString("-")
  }

  /** Parses a typed or scanned recovery code, forgiving case, spaces, dashes and I/L/O look-alikes. */
  fun parseRecoveryCode(code: String): ByteArray {
    val cleaned = code.uppercase()
      .filter { it.isLetterOrDigit() }
      .map { c -> when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c } }
      .joinToString("")
    val bytes = unbase32(cleaned) ?: throw InvalidRecoveryCodeException()
    if (bytes.size != KEY_BYTES + CHECKSUM_BYTES) throw InvalidRecoveryCodeException()
    val key = bytes.copyOf(KEY_BYTES)
    val checksum = bytes.copyOfRange(KEY_BYTES, bytes.size)
    if (!sha256(key).copyOf(CHECKSUM_BYTES).contentEquals(checksum)) throw InvalidRecoveryCodeException()
    return key
  }

  private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  private fun base32(bytes: ByteArray): String {
    val out = StringBuilder()
    var buffer = 0
    var bits = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xFF)
      bits += 8
      while (bits >= 5) {
        out.append(CROCKFORD[(buffer shr (bits - 5)) and 31])
        bits -= 5
      }
    }
    if (bits > 0) out.append(CROCKFORD[(buffer shl (5 - bits)) and 31])
    return out.toString()
  }

  private fun unbase32(text: String): ByteArray? {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in text) {
      val v = CROCKFORD.indexOf(c)
      if (v < 0) return null
      buffer = ((buffer shl 5) or v) and 0xFFFF
      bits += 5
      if (bits >= 8) {
        out.write((buffer shr (bits - 8)) and 0xFF)
        bits -= 8
      }
    }
    return out.toByteArray()
  }

  // endregion

  private class Parsed(val header: Header, val prefix: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

  private fun parse(file: ByteArray): Parsed {
    if (!isEncryptedBackup(file)) throw NotABackupException()
    if (file.size < MAGIC.size + 4) throw CorruptedException()
    val headerLength = ByteBuffer.wrap(file, MAGIC.size, 4).int
    val headerEnd = MAGIC.size + 4 + headerLength
    if (headerLength <= 0 || headerEnd + NONCE_BYTES + TAG_BITS / 8 > file.size) throw CorruptedException()
    val header = runCatching {
      json.decodeFromString(Header.serializer(), String(file, MAGIC.size + 4, headerLength, Charsets.UTF_8))
    }.getOrElse { throw CorruptedException() }
    if (header.format > FORMAT_VERSION) throw UnsupportedFormatException(header.format)
    return Parsed(
      header = header,
      prefix = file.copyOf(headerEnd),
      nonce = file.copyOfRange(headerEnd, headerEnd + NONCE_BYTES),
      ciphertext = file.copyOfRange(headerEnd + NONCE_BYTES, file.size),
    )
  }

  private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
    return try {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  private fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      updateAAD(aad)
    }

  private fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

  private fun gunzip(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

  private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

  private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  private fun unb64(text: String): ByteArray =
    runCatching { Base64.getDecoder().decode(text) }.getOrElse { throw CorruptedException() }
}
