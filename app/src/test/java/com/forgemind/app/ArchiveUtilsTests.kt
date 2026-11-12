package com.forgemind.app

import com.forgemind.app.bridge.ArchiveUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveUtilsTests {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `create and extract zip roundtrip preserves nested files`() {
        val srcDir = tmp.newFolder("project")
        writeText(File(srcDir, "README.md"), "# hello")
        writeText(File(srcDir, "src/main/com/App.kt"), "fun main() {}")
        writeText(File(srcDir, "data/table.csv"), "a,b\n1,2")

        val zip = File(tmp.root, "out/project.zip")
        val created = ArchiveUtils.createZip(listOf(srcDir), zip)
        assertTrue(created.getOrThrow() == 3)
        assertTrue(zip.exists() && zip.length() > 0)

        val outDir = tmp.newFolder("extracted")
        val extracted = ArchiveUtils.extractZip(zip, outDir)
        assertEquals(3, extracted.getOrThrow())
        assertEquals("# hello", File(outDir, "project/README.md").readText())
        assertEquals("fun main() {}", File(outDir, "project/src/main/com/App.kt").readText())
        assertEquals("a,b\n1,2", File(outDir, "project/data/table.csv").readText())
    }

    @Test
    fun `create zip supports single file source`() {
        val single = tmp.newFile("report.txt").apply { writeText("body") }
        val zip = File(tmp.root, "single/report.zip")

        val count = ArchiveUtils.createZip(listOf(single), zip).getOrThrow()
        assertEquals(1, count)

        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("report.txt"), names)
        }
    }

    @Test
    fun `extract rejects zip-slip entries outside target dir`() {
        val malicious = File(tmp.root, "malicious.zip")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("safe/ok.txt"))
            zip.write("fine".toByteArray())
            zip.closeEntry()
        }

        val outDir = tmp.newFolder("slip-target")
        val result = ArchiveUtils.extractZip(malicious, outDir)

        assertEquals(1, result.getOrThrow())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(outDir.parentFile, "evil.txt").exists())
        assertEquals("fine", File(outDir, "safe/ok.txt").readText())
    }

    @Test
    fun `create zip fails for missing source and extract fails for missing archive`() {
        val missing = File(tmp.root, "nope")
        assertTrue(ArchiveUtils.createZip(listOf(missing), File(tmp.root, "a.zip")).isFailure)
        assertTrue(ArchiveUtils.extractZip(missing, tmp.newFolder("b")).isFailure)
    }
}
