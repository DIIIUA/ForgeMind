package com.forgemind.app

import com.forgemind.app.data.archive.SettingsBackupCipher
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupCipherTests {
    private val password = "test-only password 0123".toCharArray()
    private val plain = "{\"api_key\":\"SYNTHETIC-SECRET\",\"language\":\"ru\"}".toByteArray()

    @Test fun roundTripHasNoPlaintextSecret() {
        val encrypted = SettingsBackupCipher.encrypt(plain, password)
        assertArrayEquals(plain, SettingsBackupCipher.decrypt(encrypted, password))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("SYNTHETIC-SECRET"))
    }
    @Test fun wrongPasswordIsRejected() {
        val encrypted = SettingsBackupCipher.encrypt(plain, password)
        assertThrows(Exception::class.java) { SettingsBackupCipher.decrypt(encrypted, "wrong password 0123".toCharArray()) }
    }
    @Test fun tamperedPayloadIsRejected() {
        val encrypted = SettingsBackupCipher.encrypt(plain, password)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SettingsBackupCipher.decrypt(encrypted, password) }
    }
    @Test fun alteredSaltIsRejected() {
        val encrypted = SettingsBackupCipher.encrypt(plain, password)
        encrypted[24] = (encrypted[24].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SettingsBackupCipher.decrypt(encrypted, password) }
    }
    @Test fun truncatedOrUnknownFormatIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCipher.decrypt(byteArrayOf(1,2), password) }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCipher.decrypt(ByteArray(80), password) }
    }
    @Test fun repeatedExportUsesFreshSaltAndIv() {
        assertFalse(SettingsBackupCipher.encrypt(plain, password).contentEquals(SettingsBackupCipher.encrypt(plain, password)))
    }
    @Test fun shortPasswordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCipher.encrypt(plain, charArrayOf('x')) }
    }
}
