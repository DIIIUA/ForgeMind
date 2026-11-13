package com.forgemind.app.bridge

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure JVM ZIP helpers used by the agent's archive tools (internal workspace).
 * Kept free of Android dependencies so it is directly unit-testable.
 */
object ArchiveUtils {

    /**
     * Create a .zip archive at [outZip] containing [sources] (files and/or directories).
     * Entry names are relative to each source's parent directory, so a directory source
     * keeps its own name as the leading path segment (extracting recreates the folder).
     * Returns the number of regular files written into the archive.
     */
    fun createZip(sources: List<File>, outZip: File): Result<Int> = runCatching {
        require(sources.isNotEmpty()) { "No source files specified for archiving" }
        outZip.parentFile?.let { if (!it.exists()) it.mkdirs() }
        var count = 0
        ZipOutputStream(FileOutputStream(outZip)).use { zip ->
            sources.forEach { src ->
                require(src.exists()) { "Not found: ${src.absolutePath}" }
                val baseDir = src.parentFile ?: File(".")
                val baseCanon = baseDir.canonicalFile
                val files = if (src.isDirectory) {
                    src.walkTopDown().filter { it.isFile }.toList()
                } else {
                    listOf(src)
                }
                files.forEach { f ->
                    val entryName = f.canonicalFile.relativeTo(baseCanon).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        count
    }

    /**
     * Extract [zipFile] into [targetDir] (created if missing).
     * Protects against zip-slip: entries resolving outside [targetDir] are rejected.
     * Returns the number of extracted regular files.
     */
    fun extractZip(zipFile: File, targetDir: File): Result<Int> = runCatching {
        require(zipFile.exists()) { "Archive not found: ${zipFile.absolutePath}" }
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetCanon = targetDir.canonicalFile
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.isBlank() || name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                val out = File(targetDir, name)
                if (out.canonicalPath != targetCanon.canonicalPath &&
                    !out.canonicalPath.startsWith(targetCanon.canonicalPath + File.separator)
                ) {
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    FileOutputStream(out).use { zip.copyTo(it) }
                    count++
                }
                zip.closeEntry()
            }
        }
        count
    }
}
