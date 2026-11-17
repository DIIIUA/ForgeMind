package com.forgemind.app.data.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable settings envelope; authentication completes before any settings are applied. */
object SettingsBackupCipher {
    private val magic = "FORGEMIND-SETTINGS-1\n".toByteArray(Charsets.US_ASCII)
    private const val MAX_PLAIN = 16 * 1024 * 1024
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16
    private const val ITERATIONS = 600_000
    private val headerSize = magic.size + SALT_SIZE + IV_SIZE

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Use at least 12 characters" }
        require(plain.size <= MAX_PLAIN) { "Settings backup is too large" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val header = magic + salt + iv
        return header + crypt(Cipher.ENCRYPT_MODE, plain, password, salt, iv, header)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size in (headerSize + TAG_SIZE)..(headerSize + TAG_SIZE + MAX_PLAIN)) { "Invalid settings backup size" }
        require(data.copyOfRange(0, magic.size).contentEquals(magic)) { "Unknown settings backup format" }
        val salt = data.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val iv = data.copyOfRange(magic.size + SALT_SIZE, headerSize)
        return crypt(Cipher.DECRYPT_MODE, data.copyOfRange(headerSize, data.size), password,
            salt, iv, data.copyOfRange(0, headerSize))
    }

    fun readBounded(input: InputStream): ByteArray {
        val limit = headerSize + TAG_SIZE + MAX_PLAIN
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= limit) { "Settings backup is too large" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun crypt(mode: Int, data: ByteArray, password: CharArray, salt: ByteArray,
                      iv: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(header)
                doFinal(data)
            }
        } finally { key.fill(0) }
    }
}
