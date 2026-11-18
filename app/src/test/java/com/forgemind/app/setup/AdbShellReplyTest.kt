package com.forgemind.app.setup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AdbShellReplyTest {
    private val marker = "FM_" + "a".repeat(32)
    @Test fun completeReplyDoesNotWaitForLibraryCloseException() {
        val data = ByteArrayInputStream("2000\n\n$marker:0\n".toByteArray())
        val input = object : InputStream() {
            override fun read(): Int = data.read().also { if (it < 0) throw IOException("Stream closed.") }
            override fun read(b: ByteArray, off: Int, len: Int): Int = data.read(b, off, len).also { if (it < 0) throw IOException("Stream closed.") }
        }
        val reply = AdbShellReply.read(input, marker)
        assertEquals("2000", reply.text.trim()); assertEquals(0, reply.exitCode)
    }
    @Test fun fragmentedNonzeroReplyPreservesFailureStatus() {
        val data = ByteArrayInputStream("denied\r\n\r\n$marker:1\r\n".toByteArray())
        val input = object : InputStream() { override fun read() = data.read(); override fun read(b: ByteArray, off: Int, len: Int) = data.read(b, off, minOf(2,len)) }
        val reply = AdbShellReply.read(input, marker)
        assertEquals(1, reply.exitCode); assertEquals("denied", reply.text.trim())
    }
    @Test(expected = IOException::class) fun lostReplyCannotBeReportedAsSuccess() {
        AdbShellReply.read(ByteArrayInputStream("2000\n".toByteArray()),marker)
    }
    @Test(expected = IllegalArgumentException::class) fun markerCannotInjectShellSyntax() {
        AdbShellReply.command("id -u", "'; echo injected")
    }
}
