package com.forgemind.termuxbridge

import java.io.File

/** File-API guardrails, not a sandbox for an explicitly authorized shell command. */
class BridgePathPolicy(
    private val home: File,
    private val prefix: File,
    private val privateRoots: List<File>
) {
    private fun inside(path: String, root: String) = path == root || path.startsWith(root + File.separator)
    companion object {
        // Android may bind-mount these aliases, so File.canonicalPath alone cannot unify them.
        internal fun storageIdentity(path: String): String = when {
            path == "/data/user/0" -> "/data/data"
            path.startsWith("/data/user/0/") -> "/data/data/" + path.removePrefix("/data/user/0/")
            else -> path
        }
    }
    private fun identity(file: File) = storageIdentity(file.canonicalPath)

    fun lexical(raw: String): File {
        require(raw.length <= 4096 && '\u0000' !in raw) { "Invalid path" }
        val path = raw.trim()
        return when {
            path.isEmpty() || path == "~" || path == "." -> home
            path.startsWith("~/") -> File(home, path.substring(2))
            File(path).isAbsolute -> File(path)
            else -> File(home, path)
        }.absoluteFile.toPath().normalize().toFile()
    }

    fun resolve(raw: String): File {
        val result = lexical(raw).canonicalFile
        require(privateRoots.none { inside(identity(result), identity(it)) }) { "Private application data is not exposed by the bridge" }
        return result
    }

    fun mutable(raw: String): File {
        val file = resolve(raw)
        val path = identity(file)
        val protected = listOf("bin", "lib", "libexec", "etc").map { File(prefix, it) } + privateRoots
        require(protected.none { inside(path, identity(it)) || inside(identity(it), path) }) {
            "Cannot modify a protected Termux or application directory"
        }
        require(listOf(home, prefix).none { inside(identity(it), path) }) {
            "Cannot modify an environment root or its parent"
        }
        return file
    }
}
