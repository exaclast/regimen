package com.exaclast.regimen.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleCryptoTest {

  // Low iteration count keeps tests fast; real files use DEFAULT_ITERATIONS.
  private val iterations = 1_000
  private val passphrase = "correct horse battery staple".toCharArray()
  private val plaintext = """{"version":3,"medications":[{"id":"m1","formulaName":"Metformin"}]}""".toByteArray()

  @Test
  fun roundTripsWithDataKeyAndPassphrase() {
    val key = BundleCrypto.newDataKey()
    val envelope = BundleCrypto.wrapWithPassphrase(key, passphrase, iterations)
    val file = BundleCrypto.encrypt(plaintext, key, envelope)

    assertTrue(BundleCrypto.isEncryptedBackup(file))
    assertFalse(String(file, Charsets.ISO_8859_1).contains("Metformin"))
    assertArrayEquals(plaintext, BundleCrypto.decrypt(file, key))

    val (decrypted, unlockedKey) = BundleCrypto.decryptWithPassphrase(file, passphrase)
    assertArrayEquals(plaintext, decrypted)
    assertArrayEquals(key, unlockedKey)
  }

  @Test
  fun wrongPassphraseOrKeyIsRejected() {
    val key = BundleCrypto.newDataKey()
    val file = BundleCrypto.encrypt(plaintext, key, BundleCrypto.wrapWithPassphrase(key, passphrase, iterations))
    assertThrows(BundleCrypto.WrongPassphraseException::class.java) {
      BundleCrypto.decryptWithPassphrase(file, "wrong".toCharArray())
    }
    assertThrows(BundleCrypto.WrongKeyException::class.java) {
      BundleCrypto.decrypt(file, BundleCrypto.newDataKey())
    }
  }

  @Test
  fun tamperingIsDetected() {
    val key = BundleCrypto.newDataKey()
    val file = BundleCrypto.encrypt(plaintext, key, null)
    val tamperedBody = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    assertThrows(BundleCrypto.CorruptedException::class.java) { BundleCrypto.decrypt(tamperedBody, key) }

    // Editing the unencrypted header (here, its timestamp digits) also fails: it's authenticated.
    val headerText = String(file, Charsets.ISO_8859_1)
    val digit = headerText.indexOf("\"createdAtEpochMillis\":") + "\"createdAtEpochMillis\":".length
    val tamperedHeader = file.copyOf().also { it[digit] = if (it[digit] == '9'.code.toByte()) '8'.code.toByte() else '9'.code.toByte() }
    assertThrows(BundleCrypto.CorruptedException::class.java) { BundleCrypto.decrypt(tamperedHeader, key) }

    assertThrows(BundleCrypto.NotABackupException::class.java) { BundleCrypto.decrypt(plaintext, key) }
  }

  @Test
  fun recoveryCodeRoundTripsAndForgivesFormatting() {
    val key = BundleCrypto.newDataKey()
    val code = BundleCrypto.recoveryCode(key)
    assertEquals(11, code.split("-").size)
    assertArrayEquals(key, BundleCrypto.parseRecoveryCode(code))
    val sloppy = code.lowercase().replace("-", " ").replace('1', 'l').replace('0', 'o')
    assertArrayEquals(key, BundleCrypto.parseRecoveryCode(sloppy))

    val file = BundleCrypto.encrypt(plaintext, key, BundleCrypto.wrapWithPassphrase(key, passphrase, iterations))
    assertArrayEquals(plaintext, BundleCrypto.decrypt(file, BundleCrypto.parseRecoveryCode(code)))
  }

  @Test
  fun recoveryCodeTypoIsCaught() {
    val code = BundleCrypto.recoveryCode(BundleCrypto.newDataKey())
    val i = code.indexOfFirst { it.isLetterOrDigit() }
    val typo = code.replaceRange(i, i + 1, if (code[i] == 'A') "B" else "A")
    assertThrows(BundleCrypto.InvalidRecoveryCodeException::class.java) { BundleCrypto.parseRecoveryCode(typo) }
    assertThrows(BundleCrypto.InvalidRecoveryCodeException::class.java) { BundleCrypto.parseRecoveryCode("ABCDE") }
  }

  @Test
  fun changingPassphraseKeepsTheSameDataKey() {
    val key = BundleCrypto.newDataKey()
    val first = BundleCrypto.wrapWithPassphrase(key, passphrase, iterations)
    val second = BundleCrypto.wrapWithPassphrase(key, "new passphrase".toCharArray(), iterations)
    assertEquals(first.keyId, second.keyId)
    assertNotEquals(first.kdf.salt, second.kdf.salt)
    assertArrayEquals(key, BundleCrypto.unwrapWithPassphrase(second, "new passphrase".toCharArray()))
  }
}
