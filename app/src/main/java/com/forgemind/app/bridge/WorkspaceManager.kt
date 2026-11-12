package com.forgemind.app.bridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WriteOrEditResult(
    val message: String,
    val diffText: String,
    val snapshotId: String? = null
)

class WorkspaceManager(
    private val context: Context,
    private val termuxBridge: TermuxBridge,
    private val snapshotDao: FileSnapshotDao
) {
    val workspacesRoot: File by lazy {
        File(context.filesDir, "workspaces").apply {
            mkdirs()
            val def = File(this, "default")
            if (!def.exists()) {
                def.mkdirs()
                File(def, "README.md").writeText(
                    "# ForgeMind Workspace\n\nЛокальная песочница проекта. AI, редактор кода, встроенный терминал и Web Preview имеют общий доступ к этим файлам.\n"
                )
            }
        }
    }

    val chatFilesDir: File by lazy {
        File(context.filesDir, "chat_files").apply { mkdirs() }
    }

    @Volatile
    var safRootUri: Uri? = null

    fun getInternalProjectDir(projectName: String = "default"): File {
        val safe = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
        return File(workspacesRoot, safe).apply { mkdirs() }
    }

    suspend fun listFiles(
        env: WorkspaceEnvType,
        path: String = "",
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<List<WorkspaceFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    if (!target.exists()) error("Папка не найдена: $path")
                    if (!target.isDirectory) error("Это файл, а не папка: $path")
                    val baseCanon = root.canonicalFile
                    (target.listFiles() ?: emptyArray())
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                        .map { f ->
                            val rel = f.canonicalFile.relativeTo(baseCanon).invariantSeparatorsPath
                            WorkspaceFileItem(
                                name = f.name,
                                path = rel,
                                isDirectory = f.isDirectory,
                                sizeBytes = if (f.isFile) f.length() else 0L,
                                lastModified = f.lastModified(),
                                envType = env
                            )
                        }
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    termuxBridge.withSftp(sshServer) { sftp, baseDir ->
                        val remoteDir = resolveRemotePath(baseDir, path)
                        @Suppress("UNCHECKED_CAST")
                        val entries = sftp.ls(remoteDir) as List<ChannelSftp.LsEntry>
                        entries.filter { it.filename != "." && it.filename != ".." }
                            .sortedWith(compareByDescending<ChannelSftp.LsEntry> { it.attrs.isDir }
                                .thenBy { it.filename.lowercase() })
                            .map { e ->
                                val full = if (remoteDir == "/") "/${e.filename}" else "$remoteDir/${e.filename}"
                                WorkspaceFileItem(
                                    name = e.filename,
                                    path = full,
                                    isDirectory = e.attrs.isDir,
                                    sizeBytes = e.attrs.size,
                                    lastModified = e.attrs.mTime * 1000L,
                                    envType = env
                                )
                            }
                    }
                }
                WorkspaceEnvType.SAF -> {
                    val dirDoc = resolveSafDocument(path, createDirs = false)
                        ?: error("SAF-папка не выбрана или путь не найден: $path")
                    if (!dirDoc.isDirectory) error("Указанный путь не является папкой: $path")
                    dirDoc.listFiles()
                        .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
                        .map { df ->
                            val name = df.name ?: "item"
                            val childPath = if (path.isBlank()) name else "${path.trimEnd('/')}/$name"
                            WorkspaceFileItem(
                                name = name,
                                path = childPath,
                                isDirectory = df.isDirectory,
                                sizeBytes = df.length(),
                                lastModified = df.lastModified(),
                                envType = env
                            )
                        }
                }
            }
        }
    }

    suspend fun searchFiles(
        env: WorkspaceEnvType,
        query: String,
        internalProject: String = "default",
        maxResults: Int = 80,
        sshServer: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim().lowercase()
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val baseCanon = root.canonicalFile
                    root.walkTopDown()
                        .filter { it.isFile && !it.path.contains("${File.separator}.git${File.separator}") }
                        .map { it.canonicalFile.relativeTo(baseCanon).invariantSeparatorsPath }
                        .filter { q.isEmpty() || it.lowercase().contains(q) }
                        .take(maxResults)
                        .toList()
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    val cmd = "find . -maxdepth 6 -not -path '*/.git/*' -not -path '*/node_modules/*' " +
                        (if (q.isNotBlank()) "-iname ${TermuxBridge.shellSingleQuote("*$query*")}" else "") +
                        " | head -n $maxResults"
                    val res = termuxBridge.runCommand(cmd, timeoutSeconds = 15, serverHint = sshServer)
                    if (res.error != null) error(res.error)
                    res.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
                }
                WorkspaceEnvType.SAF -> {
                    val root = safRootUri?.let { DocumentFile.fromTreeUri(context, it) }
                        ?: error("SAF-папка не выбрана")
                    val matches = mutableListOf<String>()
                    fun walk(doc: DocumentFile, prefix: String) {
                        if (matches.size >= maxResults) return
                        for (child in doc.listFiles()) {
                            val name = child.name ?: continue
                            if (name == ".git" || name == "node_modules") continue
                            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                            if (child.isDirectory) {
                                walk(child, rel)
                            } else if (q.isEmpty() || rel.lowercase().contains(q)) {
                                matches += rel
                                if (matches.size >= maxResults) return
                            }
                        }
                    }
                    walk(root, "")
                    matches
                }
            }
        }
    }

    suspend fun searchCodebase(
        env: WorkspaceEnvType,
        query: String,
        internalProject: String = "default",
        maxMatches: Int = 60,
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.isBlank()) error("Поисковый запрос не может быть пустым")
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val baseCanon = root.canonicalFile
                    val hits = mutableListOf<String>()
                    root.walkTopDown()
                        .filter { it.isFile && it.length() < 2 * 1024 * 1024 && !isLikelyBinary(it.name) }
                        .forEach { file ->
                            if (hits.size >= maxMatches) return@forEach
                            val rel = file.canonicalFile.relativeTo(baseCanon).invariantSeparatorsPath
                            runCatching {
                                file.bufferedReader().useLines { seq ->
                                    seq.forEachIndexed { idx, line ->
                                        if (hits.size < maxMatches && line.contains(query, ignoreCase = true)) {
                                            hits += "$rel:${idx + 1}: ${line.trim().take(180)}"
                                        }
                                    }
                                }
                            }
                        }
                    if (hits.isEmpty()) "Совпадений для '$query' не найдено." else hits.joinToString("\n")
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    val cmd = "grep -rnIi --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build " +
                        "${TermuxBridge.shellSingleQuote(query)} . | head -n $maxMatches"
                    val res = termuxBridge.runCommand(cmd, timeoutSeconds = 20, serverHint = sshServer)
                    if (res.error != null) error(res.error)
                    res.stdout.ifBlank { "Совпадений для '$query' не найдено." }
                }
                WorkspaceEnvType.SAF -> {
                    val files = searchFiles(WorkspaceEnvType.SAF, "", internalProject, 120).getOrThrow()
                    val hits = mutableListOf<String>()
                    for (rel in files) {
                        if (hits.size >= maxMatches) break
                        if (isLikelyBinary(rel)) continue
                        val text = readRawBytes(WorkspaceEnvType.SAF, rel, internalProject).getOrNull()
                            ?.toString(Charsets.UTF_8) ?: continue
                        text.lines().forEachIndexed { idx, line ->
                            if (hits.size < maxMatches && line.contains(query, ignoreCase = true)) {
                                hits += "$rel:${idx + 1}: ${line.trim().take(180)}"
                            }
                        }
                    }
                    if (hits.isEmpty()) "Совпадений для '$query' не найдено." else hits.joinToString("\n")
                }
            }
        }
    }

    suspend fun readFile(
        env: WorkspaceEnvType,
        path: String,
        offsetLine: Int = 1,
        limitLines: Int = 300,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readRawBytes(env, path, internalProject, sshServer).getOrThrow()
            if (path.lowercase().endsWith(".pdf")) {
                return@runCatching extractPdfText(bytes, path)
            }
            if (isLikelyBinary(path) || looksBinaryBytes(bytes)) {
                error("Файл '$path' является бинарным (${bytes.size} байт). Используйте send_file_to_chat или специализированный инструмент.")
            }
            val text = bytes.toString(Charsets.UTF_8)
            val lines = text.lines()
            val start = (offsetLine - 1).coerceAtLeast(0)
            if (start >= lines.size && lines.isNotEmpty()) {
                return@runCatching "Файл '$path' содержит ${lines.size} строк, смещение $offsetLine выходит за пределы."
            }
            val end = (start + limitLines.coerceIn(1, 1500)).coerceAtMost(lines.size)
            val slice = (start until end).joinToString("\n") { i -> "${i + 1}| ${lines[i]}" }
            "Файл: $path (строки ${start + 1}..$end из ${lines.size})\n$slice"
        }
    }

    suspend fun readRawText(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = runCatching {
        readRawBytes(env, path, internalProject, sshServer).getOrThrow().toString(Charsets.UTF_8)
    }

    /**
     * Create a ZIP archive in the internal workspace from a source file or folder.
     * If [archivePath] is blank, the archive is created next to the source as `<name>.zip`.
     */
    suspend fun createZipArchive(
        sourcePath: String,
        archivePath: String = "",
        internalProject: String = "default"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getInternalProjectDir(internalProject)
            val src = resolveSafeInternal(root, sourcePath)
            if (!src.exists()) error("Не найдено: $sourcePath")
            val defaultName = (if (src.isDirectory) src.name else src.nameWithoutExtension) + ".zip"
            val dst = resolveSafeInternal(root, archivePath.ifBlank { defaultName })
            val dstPath = dst.absolutePath.let { if (it.lowercase().endsWith(".zip")) it else "$it.zip" }
            val dstFile = File(dstPath)
            val count = ArchiveUtils.createZip(listOf(src), dstFile).getOrThrow()
            "Создан архив '${dstFile.name}' (${dstFile.length()} байт, файлов: $count) из '$sourcePath'."
        }
    }

    /** Extract a ZIP archive from the internal workspace into a target folder (default: folder named after the archive). */
    suspend fun extractZipArchive(
        archivePath: String,
        targetDirPath: String = "",
        internalProject: String = "default"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getInternalProjectDir(internalProject)
            val zip = resolveSafeInternal(root, archivePath)
            if (!zip.exists() || zip.isDirectory) error("Архив не найден: $archivePath")
            val defaultTarget = zip.nameWithoutExtension.ifBlank { "extracted" }
            val target = resolveSafeInternal(root, targetDirPath.ifBlank { defaultTarget })
            if (!target.isDirectory && target.exists()) error("Целевой путь — не папка: $targetDirPath")
            val usedTarget = targetDirPath.ifBlank { defaultTarget }
            val count = ArchiveUtils.extractZip(zip, target).getOrThrow()
            val top = target.listFiles()?.take(12)?.joinToString(", ") { it.name }.orEmpty()
            "Архив '${zip.name}' распакован в '$usedTarget' (файлов: $count). Содержимое: $top"
        }
    }

    suspend fun readRawBytes(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    if (!target.exists() || !target.isFile) error("Файл не найден: $path")
                    target.readBytes()
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    termuxBridge.withSftp(sshServer) { sftp, baseDir ->
                        val remote = resolveRemotePath(baseDir, path)
                        val out = ByteArrayOutputStream()
                        sftp.get(remote, out)
                        out.toByteArray()
                    }
                }
                WorkspaceEnvType.SAF -> {
                    val doc = resolveSafDocument(path, createDirs = false)
                        ?: error("SAF файл не найден: $path")
                    context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                        ?: error("Не удалось открыть SAF поток для $path")
                }
            }
        }
    }

    suspend fun writeFile(
        env: WorkspaceEnvType,
        path: String,
        content: String,
        sessionId: String? = null,
        messageId: String? = null,
        saveSnapshot: Boolean = true,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<WriteOrEditResult> = withContext(Dispatchers.IO) {
        runCatching {
            val prevBytes = readRawBytes(env, path, internalProject, sshServer).getOrNull()
            val existed = prevBytes != null
            val prevText = if (prevBytes != null && !looksBinaryBytes(prevBytes)) {
                prevBytes.toString(Charsets.UTF_8)
            } else null

            writeRawBytes(env, path, content.toByteArray(Charsets.UTF_8), internalProject, sshServer).getOrThrow()

            val diff = DiffUtils.generateUnifiedDiff(path, prevText, content)
            var snapId: String? = null
            if (saveSnapshot && sessionId != null && messageId != null) {
                snapId = UUID.randomUUID().toString()
                snapshotDao.upsert(
                    FileSnapshotEntity(
                        id = snapId,
                        sessionId = sessionId,
                        messageId = messageId,
                        envType = env.id,
                        filePath = path,
                        fileExistedBefore = existed,
                        previousContent = prevText,
                        newContent = content,
                        diffPreview = diff
                    )
                )
            }
            WriteOrEditResult(
                message = "Файл '$path' успешно ${if (existed) "обновлён" else "создан"} (${content.length} символов).",
                diffText = diff,
                snapshotId = snapId
            )
        }
    }

    suspend fun editFile(
        env: WorkspaceEnvType,
        path: String,
        oldString: String,
        newString: String,
        sessionId: String? = null,
        messageId: String? = null,
        saveSnapshot: Boolean = true,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<WriteOrEditResult> = withContext(Dispatchers.IO) {
        runCatching {
            val current = readRawText(env, path, internalProject, sshServer).getOrThrow()
            val occurrences = countOccurrences(current, oldString)
            if (occurrences == 0) {
                error("Фрагмент old_string не найден в файле '$path'. Прочитайте файл через file_read для точного совпадения.")
            }
            if (occurrences > 1) {
                error("Фрагмент old_string встречается в '$path' $occurrences раз(а). Добавьте больше контекстных строк.")
            }
            val updated = current.replaceFirst(oldString, newString)
            writeRawBytes(env, path, updated.toByteArray(Charsets.UTF_8), internalProject, sshServer).getOrThrow()
            val diff = DiffUtils.generateUnifiedDiff(path, current, updated)
            var snapId: String? = null
            if (saveSnapshot && sessionId != null && messageId != null) {
                snapId = UUID.randomUUID().toString()
                snapshotDao.upsert(
                    FileSnapshotEntity(
                        id = snapId,
                        sessionId = sessionId,
                        messageId = messageId,
                        envType = env.id,
                        filePath = path,
                        fileExistedBefore = true,
                        previousContent = current,
                        newContent = updated,
                        diffPreview = diff
                    )
                )
            }
            WriteOrEditResult(
                message = "Правка в '$path' успешно применена.",
                diffText = diff,
                snapshotId = snapId
            )
        }
    }

    suspend fun restoreSnapshot(snapshotId: String, internalProject: String = "default"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snap = snapshotDao.getById(snapshotId) ?: error("Снэпшот не найден")
                val env = WorkspaceEnvType.fromId(snap.envType)
                if (snap.fileExistedBefore && snap.previousContent != null) {
                    writeRawBytes(env, snap.filePath, snap.previousContent.toByteArray(Charsets.UTF_8), internalProject).getOrThrow()
                } else {
                    deleteFile(env, snap.filePath, internalProject).getOrThrow()
                }
                snapshotDao.markRestored(snapshotId)
                "Откат выполнен: ${snap.filePath}"
            }
        }

    suspend fun writeRawBytes(
        env: WorkspaceEnvType,
        path: String,
        bytes: ByteArray,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    termuxBridge.withSftp(sshServer) { sftp, baseDir ->
                        val remote = resolveRemotePath(baseDir, path)
                        val parent = remote.substringBeforeLast('/', "")
                        if (parent.isNotBlank()) mkdirsSftp(sftp, parent)
                        sftp.put(ByteArrayInputStream(bytes), remote, ChannelSftp.OVERWRITE)
                    }
                }
                WorkspaceEnvType.SAF -> {
                    val rootUri = safRootUri ?: error("SAF-папка не выбрана")
                    val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                        ?: error("Не удалось открыть корень SAF")
                    val parts = path.trim('/').split('/').filter { it.isNotBlank() }
                    if (parts.isEmpty()) error("Некорректный путь SAF")
                    var cur = rootDoc
                    for (dir in parts.dropLast(1)) {
                        cur = cur.findFile(dir) ?: cur.createDirectory(dir)
                            ?: error("Не удалось создать папку $dir")
                    }
                    val fileName = parts.last()
                    writeSafBytes(cur, fileName, bytes)
                }
            }
        }
    }

    private fun writeSafBytes(parent: DocumentFile, fileName: String, bytes: ByteArray) {
        val existing = parent.findFile(fileName)
        if (existing != null) {
            val wroteExisting = tryWriteUri(existing.uri, bytes, modes = listOf("wt", "rwt"))
            if (wroteExisting) return
            if (!existing.delete()) {
                error("Не удалось перезаписать SAF-файл $fileName")
            }
        }

        val mime = guessMime(fileName)
        var created = parent.createFile(mime, fileName)
            ?: parent.createFile("application/octet-stream", fileName)
            ?: error("Не удалось создать SAF-файл $fileName")

        if (created.name != fileName) {
            runCatching { created.renameTo(fileName) }
            parent.findFile(fileName)?.let { created = it }
        }

        if (!tryWriteUri(created.uri, bytes, modes = listOf("wt", "rwt", "w"))) {
            error("Не удалось записать SAF-файл $fileName")
        }
    }

    private fun tryWriteUri(uri: Uri, bytes: ByteArray, modes: List<String>): Boolean {
        for (mode in modes) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, mode)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    true
                } ?: false
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    suspend fun deleteFile(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    if (target.canonicalFile == root.canonicalFile) error("Нельзя удалить корень workspace")
                    if (!target.exists()) error("Файл/папка не найдены: $path")
                    if (!target.deleteRecursively()) error("Не удалось удалить: $path")
                    "Удалено: $path"
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    val remote = termuxBridge.withSftp(sshServer) { _, baseDir -> resolveRemotePath(baseDir, path) }
                    val res = termuxBridge.runCommand("rm -rf ${TermuxBridge.shellSingleQuote(remote)}", serverHint = sshServer)
                    if (res.error != null || res.exitCode != 0) {
                        error(res.stderr.ifBlank { res.error ?: "Ошибка удаления" })
                    }
                    "Удалено в Termux/SSH: $path"
                }
                WorkspaceEnvType.SAF -> {
                    val doc = resolveSafDocument(path, createDirs = false) ?: error("Не найдено: $path")
                    if (!doc.delete()) error("Не удалось удалить SAF объект: $path")
                    "Удалено в SAF: $path"
                }
            }
        }
    }

    suspend fun createFolder(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    if (!target.exists() && !target.mkdirs()) error("Не удалось создать папку $path")
                    "Папка создана: $path"
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    termuxBridge.withSftp(sshServer) { sftp, baseDir ->
                        mkdirsSftp(sftp, resolveRemotePath(baseDir, path))
                    }
                    "Папка создана в Termux/SSH: $path"
                }
                WorkspaceEnvType.SAF -> {
                    val rootUri = safRootUri ?: error("SAF-папка не выбрана")
                    var cur = DocumentFile.fromTreeUri(context, rootUri) ?: error("Корень SAF недоступен")
                    for (seg in path.trim('/').split('/').filter { it.isNotBlank() }) {
                        cur = cur.findFile(seg) ?: cur.createDirectory(seg)
                            ?: error("Ошибка создания папки $seg")
                    }
                    "Папка создана в SAF: $path"
                }
            }
        }
    }

    suspend fun getFileInfo(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val target = resolveSafeInternal(root, path)
                    if (!target.exists()) error("Объект не найден: $path")
                    val kind = if (target.isDirectory) "directory" else "file"
                    val lines = if (target.isFile && !isLikelyBinary(target.name) && target.length() < 2 * 1024 * 1024) {
                        runCatching { target.readLines().size }.getOrNull()
                    } else null
                    buildString {
                        appendLine("Среда: internal")
                        appendLine("Путь: $path")
                        appendLine("Абсолютный путь: ${target.canonicalPath}")
                        appendLine("Тип: $kind")
                        appendLine("Размер: ${target.length()} байт")
                        if (lines != null) appendLine("Строк: $lines")
                        if (target.isDirectory) appendLine("Элементов внутри: ${target.listFiles()?.size ?: 0}")
                        appendLine("Изменён: ${fmt.format(Date(target.lastModified()))}")
                    }.trimEnd()
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    val remote = termuxBridge.withSftp(sshServer) { _, baseDir -> resolveRemotePath(baseDir, path) }
                    val res = termuxBridge.runCommand(
                        "ls -ld --full-time ${TermuxBridge.shellSingleQuote(remote)} 2>/dev/null || stat ${TermuxBridge.shellSingleQuote(remote)}",
                        timeoutSeconds = 12,
                        serverHint = sshServer
                    )
                    if (res.error != null || res.exitCode != 0) {
                        error(res.stderr.ifBlank { res.error ?: "Объект не найден: $path" })
                    }
                    "Среда: termux_ssh\nПуть: $remote\n${res.stdout.trim()}"
                }
                WorkspaceEnvType.SAF -> {
                    val doc = resolveSafDocument(path, createDirs = false) ?: error("SAF объект не найден: $path")
                    buildString {
                        appendLine("Среда: saf")
                        appendLine("Путь: $path")
                        appendLine("Имя: ${doc.name ?: "-"}")
                        appendLine("Тип: ${if (doc.isDirectory) "directory" else doc.type ?: "file"}")
                        appendLine("Размер: ${doc.length()} байт")
                        appendLine("Изменён: ${fmt.format(Date(doc.lastModified()))}")
                        if (doc.isDirectory) appendLine("Элементов внутри: ${doc.listFiles().size}")
                    }.trimEnd()
                }
            }
        }
    }

    suspend fun moveOrRenameFile(
        env: WorkspaceEnvType,
        sourcePath: String,
        targetPath: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (sourcePath.isBlank() || targetPath.isBlank()) error("Нужны source_path и target_path")
            when (env) {
                WorkspaceEnvType.INTERNAL -> {
                    val root = getInternalProjectDir(internalProject)
                    val src = resolveSafeInternal(root, sourcePath)
                    val dst = resolveSafeInternal(root, targetPath)
                    if (!src.exists()) error("Источник не найден: $sourcePath")
                    if (src.canonicalFile == root.canonicalFile) error("Нельзя переместить корень workspace")
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) error("Цель уже существует: $targetPath")
                    val moved = src.renameTo(dst)
                    if (!moved) {
                        if (src.isDirectory) {
                            src.copyRecursively(dst, overwrite = false)
                            src.deleteRecursively()
                        } else {
                            src.copyTo(dst, overwrite = false)
                            src.delete()
                        }
                    }
                    "Перемещено: '$sourcePath' -> '$targetPath'"
                }
                WorkspaceEnvType.TERMUX_SSH -> {
                    val (srcRemote, dstRemote) = termuxBridge.withSftp(sshServer) { _, baseDir ->
                        resolveRemotePath(baseDir, sourcePath) to resolveRemotePath(baseDir, targetPath)
                    }
                    val dstParent = dstRemote.substringBeforeLast('/', "")
                    val mkdirPrefix = if (dstParent.isNotBlank()) "mkdir -p ${TermuxBridge.shellSingleQuote(dstParent)} && " else ""
                    val res = termuxBridge.runCommand(
                        "${mkdirPrefix}mv ${TermuxBridge.shellSingleQuote(srcRemote)} ${TermuxBridge.shellSingleQuote(dstRemote)}",
                        timeoutSeconds = 15,
                        serverHint = sshServer
                    )
                    if (res.error != null || res.exitCode != 0) {
                        error(res.stderr.ifBlank { res.error ?: "Ошибка перемещения" })
                    }
                    "Перемещено в Termux/SSH: '$sourcePath' -> '$targetPath'"
                }
                WorkspaceEnvType.SAF -> {
                    val srcClean = sourcePath.trim('/')
                    val dstClean = targetPath.trim('/')
                    val srcParent = srcClean.substringBeforeLast('/', "")
                    val dstParent = dstClean.substringBeforeLast('/', "")
                    val doc = resolveSafDocument(srcClean, createDirs = false) ?: error("Источник SAF не найден: $sourcePath")
                    if (srcParent == dstParent) {
                        val newName = dstClean.substringAfterLast('/')
                        if (!doc.renameTo(newName)) error("Не удалось переименовать SAF-объект")
                        "Переименовано в SAF: '$sourcePath' -> '$targetPath'"
                    } else {
                        if (doc.isDirectory) error("Перенос папок между каталогами SAF не поддерживается — используйте пофайлово")
                        val bytes = readRawBytes(WorkspaceEnvType.SAF, srcClean, internalProject).getOrThrow()
                        writeRawBytes(WorkspaceEnvType.SAF, dstClean, bytes, internalProject).getOrThrow()
                        doc.delete()
                        "Перенесено в SAF: '$sourcePath' -> '$targetPath'"
                    }
                }
            }
        }
    }

    suspend fun listSessionSnapshots(sessionId: String, limit: Int = 20): List<FileSnapshotEntity> =
        withContext(Dispatchers.IO) {
            snapshotDao.getRecentForSession(sessionId, limit)
        }

    suspend fun transferFile(
        sourceEnv: WorkspaceEnvType,
        sourcePath: String,
        targetEnv: WorkspaceEnvType,
        targetPath: String,
        internalProject: String = "default",
        sourceSshServer: String? = null,
        targetSshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val data = readRawBytes(sourceEnv, sourcePath, internalProject, sourceSshServer).getOrThrow()
            writeRawBytes(targetEnv, targetPath, data, internalProject, targetSshServer).getOrThrow()
            "Передано ${data.size} байт из ${sourceEnv.label}:$sourcePath в ${targetEnv.label}:$targetPath"
        }
    }

    suspend fun importDeviceUriToEnv(
        uri: Uri,
        targetEnv: WorkspaceEnvType,
        targetDirPath: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "uploaded_${System.currentTimeMillis()}"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось прочитать выбранный файл")
            val dest = if (targetDirPath.isBlank()) fileName else "${targetDirPath.trimEnd('/')}/$fileName"
            writeRawBytes(targetEnv, dest, bytes, internalProject, sshServer).getOrThrow()
            "Загружено '$fileName' (${bytes.size} байт) -> ${targetEnv.label}: $dest"
        }
    }

    suspend fun importUriToChatAttachment(
        uri: Uri,
        internalProject: String = "default"
    ): Result<ChatAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
            val cleanName = rawName.substringAfterLast('/').substringAfterLast('\\')
                .replace("..", "_").ifBlank { "file_${System.currentTimeMillis()}" }
            val mime = context.contentResolver.getType(uri) ?: guessMime(cleanName)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось прочитать выбранный файл")

            // Сохраняем в домашнюю директорию ИИ (internal workspace) чтобы файлы были доступны агенту и во "Файлах ИИ"
            val homeDir = getInternalProjectDir(internalProject)
            val targetFile = resolveNonCollidingHomeFile(homeDir, cleanName, bytes)
            if (!targetFile.exists()) {
                targetFile.writeBytes(bytes)
            }
            buildChatAttachment(targetFile, mime, targetFile.name, isFromAssistant = false)
        }
    }

    private fun resolveNonCollidingHomeFile(homeDir: File, fileName: String, bytes: ByteArray): File {
        val candidate = File(homeDir, fileName)
        if (!candidate.exists()) return candidate
        if (candidate.length() == bytes.size.toLong() && runCatching { candidate.readBytes().contentEquals(bytes) }.getOrDefault(false)) {
            return candidate
        }
        val dotIdx = fileName.lastIndexOf('.')
        val stem = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
        val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
        var counter = 1
        while (counter < 1000) {
            val alt = File(homeDir, "${stem}_$counter$ext")
            if (!alt.exists()) return alt
            counter++
        }
        return File(homeDir, "${System.currentTimeMillis()}_$fileName")
    }

    suspend fun prepareAssistantOutgoingFile(
        env: WorkspaceEnvType,
        path: String,
        internalProject: String = "default",
        sshServer: String? = null
    ): Result<ChatAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readRawBytes(env, path, internalProject, sshServer).getOrThrow()
            val name = path.substringAfterLast('/').ifBlank { "artifact.bin" }
            val outFile = File(chatFilesDir, "ai_${System.currentTimeMillis()}_${name.replace("..", "_")}")
            outFile.writeBytes(bytes)
            buildChatAttachment(outFile, guessMime(name), name, isFromAssistant = true)
        }
    }

    suspend fun exportLocalFileToUri(localPath: String, targetUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(localPath)
            if (!f.exists()) error("Локальный файл не найден")
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: error("Не удалось открыть поток записи")
            Unit
        }
    }

    fun buildAttachmentFromLocalFile(
        file: File,
        isFromAssistant: Boolean = true,
        fallbackMime: String? = null
    ): ChatAttachment {
        val rawMime = fallbackMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMime(file.name).takeIf { it != "text/plain" || !isLikelyBinary(file.name) }
            ?: "application/octet-stream"
        return buildChatAttachment(file, rawMime, file.name, isFromAssistant = isFromAssistant)
    }

    private fun detectImageMimeByHeader(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() < 4L) return null
        return runCatching {
            val buf = ByteArray(12)
            val n = file.inputStream().use { it.read(buf) }
            when {
                n >= 8 && buf[0] == 0x89.toByte() && buf[1] == 0x50.toByte() &&
                    buf[2] == 0x4E.toByte() && buf[3] == 0x47.toByte() -> "image/png"
                n >= 3 && buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte() &&
                    buf[2] == 0xFF.toByte() -> "image/jpeg"
                n >= 12 && buf[0] == 'R'.code.toByte() && buf[1] == 'I'.code.toByte() &&
                    buf[2] == 'F'.code.toByte() && buf[3] == 'F'.code.toByte() &&
                    buf[8] == 'W'.code.toByte() && buf[9] == 'E'.code.toByte() &&
                    buf[10] == 'B'.code.toByte() && buf[11] == 'P'.code.toByte() -> "image/webp"
                n >= 6 && buf[0] == 'G'.code.toByte() && buf[1] == 'I'.code.toByte() &&
                    buf[2] == 'F'.code.toByte() && buf[3] == '8'.code.toByte() -> "image/gif"
                else -> null
            }
        }.getOrNull()
    }

    private fun buildChatAttachment(
        file: File,
        mime: String,
        name: String,
        isFromAssistant: Boolean
    ): ChatAttachment {
        val lowerName = name.lowercase()
        val headerImageMime = if (!mime.startsWith("image/")) detectImageMimeByHeader(file) else null
        val effectiveMime = when {
            headerImageMime != null -> headerImageMime
            mime == "application/octet-stream" || mime.isBlank() -> guessMime(name)
            else -> mime
        }
        val kind = when {
            effectiveMime.startsWith("image/") || lowerName.endsWithAny(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp") -> AttachmentKind.IMAGE
            effectiveMime == "application/pdf" || lowerName.endsWith(".pdf") -> AttachmentKind.PDF
            effectiveMime.startsWith("audio/") || lowerName.endsWithAny(".wav", ".mp3", ".m4a", ".ogg") -> AttachmentKind.AUDIO
            !isLikelyBinary(name) && file.length() < 512 * 1024 -> AttachmentKind.CODE_TEXT
            else -> AttachmentKind.BINARY
        }
        val preview = when (kind) {
            AttachmentKind.CODE_TEXT -> runCatching { file.readText().take(8000) }.getOrNull()
            AttachmentKind.PDF -> runCatching { extractPdfText(file.readBytes(), name).take(8000) }.getOrNull()
            else -> null
        }
        return ChatAttachment(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = effectiveMime,
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            kind = kind,
            previewText = preview,
            isFromAssistant = isFromAssistant
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extractPdfText(bytes: ByteArray, path: String): String =
        runCatching {
            PDDocument.load(bytes).use { doc ->
                val text = PDFTextStripper().getText(doc)
                "PDF '$path' (${doc.numberOfPages} стр.):\n\n$text"
            }
        }.getOrElse { "Ошибка чтения PDF '$path': ${it.message}" }

    private fun resolveSafeInternal(root: File, relPath: String): File {
        val clean = relPath.trim().trimStart('/')
        val target = if (clean.isEmpty() || clean == ".") root else File(root, clean)
        val rootCanon = root.canonicalPath
        val targetCanon = target.canonicalPath
        if (targetCanon != rootCanon && !targetCanon.startsWith(rootCanon + File.separator)) {
            error("Выход за пределы workspace запрещён: $relPath")
        }
        return target
    }

    private fun resolveRemotePath(baseDir: String, path: String): String {
        val clean = path.trim()
        return when {
            clean.isEmpty() || clean == "." -> baseDir
            clean.startsWith("/") -> clean
            clean.startsWith("~/") -> "${baseDir.trimEnd('/')}/${clean.removePrefix("~/")}"
            else -> "${baseDir.trimEnd('/')}/${clean.trimStart('/')}"
        }
    }

    private fun mkdirsSftp(sftp: ChannelSftp, path: String) {
        val parts = path.split('/').filter { it.isNotBlank() }
        var cur = if (path.startsWith("/")) "" else "."
        for (p in parts) {
            cur = "$cur/$p"
            try {
                sftp.stat(cur)
            } catch (_: SftpException) {
                sftp.mkdir(cur)
            }
        }
    }

    private fun resolveSafDocument(relPath: String, createDirs: Boolean): DocumentFile? {
        val uri = safRootUri ?: return null
        var cur = DocumentFile.fromTreeUri(context, uri) ?: return null
        val parts = relPath.trim('/').split('/').filter { it.isNotBlank() }
        for (p in parts) {
            val next = cur.findFile(p)
            cur = next ?: if (createDirs) cur.createDirectory(p) ?: return null else return null
        }
        return cur
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any { this.endsWith(it) }

    companion object {
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "mp3", "wav", "ogg", "m4a",
            "mp4", "mkv", "mov", "avi", "zip", "rar", "7z", "tar", "gz", "apk", "apks",
            "so", "dex", "class", "jar", "exe", "bin", "db", "sqlite", "ttf", "otf", "woff2", "gguf", "litertlm", "mnn"
        )

        fun isLikelyBinary(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in BINARY_EXT

        fun looksBinaryBytes(bytes: ByteArray): Boolean {
            val sample = bytes.take(1024)
            return sample.any { it.toInt() == 0 }
        }

        fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "md" -> "text/markdown"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "text/plain"
        }

        fun formatSafTreeLabel(uriString: String?): String {
            if (uriString.isNullOrBlank()) return "Не выбрана"
            val treePart = uriString
                .substringAfter("/tree/", missingDelimiterValue = "")
                .substringBefore("/document/")
                .substringBefore('?')
                .substringBefore('#')
            if (treePart.isBlank()) return uriString
            val decoded = runCatching {
                URLDecoder.decode(treePart.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(treePart)
            return when {
                decoded.startsWith("primary:") -> {
                    val sub = decoded.removePrefix("primary:").trim('/')
                    if (sub.isBlank()) "Память телефона (корень)" else "Память телефона / $sub"
                }
                decoded.startsWith("home:") -> {
                    val sub = decoded.removePrefix("home:").trim('/')
                    if (sub.isBlank()) "Документы" else "Документы / $sub"
                }
                decoded.startsWith("raw:") -> decoded.removePrefix("raw:")
                decoded.contains(':') -> {
                    val vol = decoded.substringBefore(':')
                    val sub = decoded.substringAfter(':').trim('/')
                    if (sub.isBlank()) "$vol (корень)" else "$vol / $sub"
                }
                else -> decoded
            }
        }
    }
}
