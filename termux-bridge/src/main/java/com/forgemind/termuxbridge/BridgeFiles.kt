package com.forgemind.termuxbridge

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class BridgeFiles(context: Context) {
    private val policy = BridgePathPolicy(File(TermuxBridgeService.HOME), File(TermuxBridgeService.PREFIX),
        listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir,
            File("/data/data/com.forgemind.app"), File("/data/user_de/0/com.forgemind.app")))

    fun open(path: String, mode: String): ParcelFileDescriptor {
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> error("Unsupported file mode")
        }
        val file = if (mode == "r") policy.resolve(path) else policy.mutable(path)
        require(!file.isDirectory) { "Path is a directory" }
        if (mode != "r") {
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create parent directory" }
            policy.mutable(file.path)
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    private fun stat(file: File): Bundle = Bundle().apply {
        putString("name", file.name); putString("path", file.path)
        putBoolean("exists", file.exists()); putBoolean("directory", file.isDirectory)
        putLong("size", if (file.isFile) file.length() else 0)
        putLong("modified", file.lastModified())
    }

    fun execute(request: Bundle): Bundle {
        val path = request.getString("path").orEmpty()
        return when (request.getString("action")) {
            "stat" -> stat(policy.resolve(path))
            "list" -> {
                val dir = policy.resolve(path)
                require(dir.isDirectory) { "Directory is unavailable" }
                val offset = request.getInt("offset", 0)
                require(offset in 0..100_000)
                val rows = (dir.listFiles() ?: error("Cannot list directory"))
                    .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                Bundle().apply {
                    putParcelableArrayList("entries", ArrayList(rows.drop(offset).take(64).map(::stat)))
                    putInt("nextOffset", if (offset + 64 < rows.size) offset + 64 else -1)
                    putInt("total", rows.size)
                }
            }
            "mkdir" -> {
                val file = policy.mutable(path)
                check(file.isDirectory || file.mkdirs()) { "Cannot create directory" }
                stat(file)
            }
            "move" -> {
                policy.mutable(path)
                val source = policy.lexical(path)
                val target = request.getString("destination") ?: error("Destination required")
                policy.mutable(target)
                val destination = policy.lexical(target)
                require(source.exists()) { "Source is unavailable" }
                require(!destination.exists()) { "Destination already exists" }
                require(!destination.path.startsWith(source.path + File.separator)) { "Cannot move a directory into itself" }
                check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                Files.move(source.toPath(), destination.toPath())
                stat(destination)
            }
            "delete" -> {
                require(request.getBoolean("userConfirmed", false)) { "Deletion requires explicit user confirmation" }
                policy.mutable(path)
                val file = policy.lexical(path)
                // No following directory symlinks during traversal. Revalidate each affected path.
                fun remove(item: File) {
                    policy.mutable(item.path)
                    if (Files.isDirectory(item.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        for (child in item.listFiles() ?: error("Cannot list directory")) remove(child)
                    }
                    Files.deleteIfExists(item.toPath())
                }
                remove(file)
                Bundle().apply { putBoolean("deleted", true) }
            }
            else -> error("Unsupported file operation")
        }
    }
}
