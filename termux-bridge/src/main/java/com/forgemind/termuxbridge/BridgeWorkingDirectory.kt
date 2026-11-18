package com.forgemind.termuxbridge

import java.io.File

internal object BridgeWorkingDirectory {
    fun resolve(raw: String?, home: File = File(TermuxBridgeService.HOME)): File {
        val path = raw?.trim().orEmpty()
        require(path.length <= 4096 && '\u0000' !in path) { "Invalid working directory" }
        return when {
            path.isEmpty() || path == "~" || path == "." -> home
            path.startsWith("~/") -> File(home,path.substring(2))
            File(path).isAbsolute -> File(path)
            else -> File(home,path)
        }.canonicalFile
    }
}
