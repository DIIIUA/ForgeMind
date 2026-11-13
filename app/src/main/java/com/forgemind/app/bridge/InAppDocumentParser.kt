package com.forgemind.app.bridge

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedDocumentResult(
    val text: String,
    val pageCount: Int = 0,
    val entryCount: Int = 0
)

/**
 * Pure Kotlin / Android parser for previewing documents (PDF, DOCX, XLSX, PPTX, ODT, RTF)
 * and archives (ZIP/APK/JAR) inside ForgeMind without requiring external viewer apps.
 */
object InAppDocumentParser {

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "ico")
    private val PDF_EXTS = setOf("pdf")
    private val OFFICE_EXTS = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "rtf", "doc", "xls", "ppt")
    private val MARKDOWN_EXTS = setOf("md", "markdown")
    private val ARCHIVE_EXTS = setOf("zip", "jar", "apk", "aar")

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isImageExtension(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTS
    fun isPdfExtension(fileName: String): Boolean = extensionOf(fileName) in PDF_EXTS
    fun isOfficeDocumentExtension(fileName: String): Boolean = extensionOf(fileName) in OFFICE_EXTS
    fun isMarkdownExtension(fileName: String): Boolean = extensionOf(fileName) in MARKDOWN_EXTS
    fun isArchiveExtension(fileName: String): Boolean = extensionOf(fileName) in ARCHIVE_EXTS

    fun cacheBytesForPreview(cacheDir: File, uniqueKey: String, fileName: String, bytes: ByteArray): File {
        val dir = File(cacheDir, "previews").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uniqueKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "file.bin" }
        return File(dir, "${hash}_$safeName").apply { writeBytes(bytes) }
    }

    fun parsePdf(bytes: ByteArray): ParsedDocumentResult = runCatching {
        PDDocument.load(bytes).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc).trim()
            ParsedDocumentResult(text = text, pageCount = doc.numberOfPages)
        }
    }.getOrElse { e ->
        ParsedDocumentResult(text = "PDF parse error: ${e.message}", pageCount = 0)
    }

    fun parseOfficeOrStructuredDocument(fileName: String, bytes: ByteArray): ParsedDocumentResult {
        return when (extensionOf(fileName)) {
            "docx" -> ParsedDocumentResult(text = extractDocxText(bytes))
            "xlsx" -> ParsedDocumentResult(text = extractXlsxText(bytes))
            "pptx" -> ParsedDocumentResult(text = extractPptxText(bytes))
            "odt", "ods", "odp" -> ParsedDocumentResult(text = extractOdfText(bytes))
            "rtf" -> ParsedDocumentResult(text = stripRtf(bytes.toString(Charsets.UTF_8)))
            "doc", "xls", "ppt" -> ParsedDocumentResult(text = extractLegacyBinaryText(bytes))
            else -> ParsedDocumentResult(text = bytes.toString(Charsets.UTF_8))
        }
    }

    fun listArchiveEntries(bytes: ByteArray, limit: Int = 500): ParsedDocumentResult {
        val entries = mutableListOf<String>()
        var totalEntries = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    totalEntries++
                    if (entries.size < limit) {
                        val sizeStr = if (entry.isDirectory) "dir" else "${entry.size.coerceAtLeast(0)} B"
                        entries += "${entry.name}  [$sizeStr]"
                    }
                    entry = zis.nextEntry
                }
            }
        }.onFailure { e ->
            return ParsedDocumentResult("Archive read error: ${e.message}")
        }
        return ParsedDocumentResult(
            text = entries.joinToString("\n"),
            entryCount = totalEntries
        )
    }

    private fun readZipEntriesMap(bytes: ByteArray): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return map
    }

    private fun parseXmlRoot(xmlBytes: ByteArray): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes)).documentElement
    }.getOrNull()

    internal fun extractDocxText(bytes: ByteArray): String {
        val entries = readZipEntriesMap(bytes)
        val docXml = entries["word/document.xml"] ?: return ""
        val root = parseXmlRoot(docXml) ?: return ""
        val paragraphs = mutableListOf<String>()
        val bodyChildren = root.getElementsByTagName("w:p")
        for (i in 0 until bodyChildren.length) {
            val p = bodyChildren.item(i) as? Element ?: continue
            val sb = StringBuilder()
            collectTextFromWordRuns(p, sb)
            val line = sb.toString().trimEnd()
            if (line.isNotEmpty()) paragraphs += line
        }
        return paragraphs.joinToString("\n\n")
    }

    private fun collectTextFromWordRuns(node: Node, sb: StringBuilder) {
        if (node is Element) {
            when (node.tagName) {
                "w:t", "t" -> sb.append(node.textContent ?: "")
                "w:tab", "tab" -> sb.append('\t')
                "w:br", "w:cr", "br" -> sb.append('\n')
                else -> {
                    val children = node.childNodes
                    for (j in 0 until children.length) {
                        collectTextFromWordRuns(children.item(j), sb)
                    }
                }
            }
        }
    }

    internal fun extractXlsxText(bytes: ByteArray): String {
        val entries = readZipEntriesMap(bytes)
        val sharedStrings = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { ssBytes ->
            parseXmlRoot(ssBytes)?.let { root ->
                val siList = root.getElementsByTagName("si")
                for (i in 0 until siList.length) {
                    val el = siList.item(i) as? Element ?: continue
                    val sb = StringBuilder()
                    val ts = el.getElementsByTagName("t")
                    for (k in 0 until ts.length) {
                        sb.append(ts.item(k).textContent ?: "")
                    }
                    sharedStrings += sb.toString()
                }
            }
        }
        val sheets = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        if (sheets.isEmpty()) return ""
        val out = StringBuilder()
        sheets.forEachIndexed { idx, name ->
            val root = parseXmlRoot(entries.getValue(name)) ?: return@forEachIndexed
            if (idx > 0) out.append("\n\n")
            out.append("=== ${name.substringAfterLast('/').removeSuffix(".xml")} ===\n")
            val rows = root.getElementsByTagName("row")
            for (r in 0 until rows.length) {
                val rowEl = rows.item(r) as? Element ?: continue
                val cells = rowEl.getElementsByTagName("c")
                val rowValues = mutableListOf<String>()
                for (c in 0 until cells.length) {
                    val cellEl = cells.item(c) as? Element ?: continue
                    val cellType = cellEl.getAttribute("t")
                    val vNodes = cellEl.getElementsByTagName("v")
                    val rawVal = if (vNodes.length > 0) vNodes.item(0).textContent.orEmpty() else {
                        val isNodes = cellEl.getElementsByTagName("t")
                        if (isNodes.length > 0) isNodes.item(0).textContent.orEmpty() else ""
                    }
                    val resolved = if (cellType == "s") {
                        rawVal.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: rawVal
                    } else {
                        rawVal
                    }
                    rowValues += resolved
                }
                if (rowValues.any { it.isNotBlank() }) {
                    out.append(rowValues.joinToString(" | ")).append('\n')
                }
            }
        }
        return out.toString().trim()
    }

    internal fun extractPptxText(bytes: ByteArray): String {
        val entries = readZipEntriesMap(bytes)
        val slidePattern = Regex("""ppt/slides/slide(\d+)\.xml""")
        val slides = entries.keys
            .mapNotNull { key -> slidePattern.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()?.let { it to key } }
            .sortedBy { it.first }
        if (slides.isEmpty()) return ""
        val out = StringBuilder()
        for ((num, path) in slides) {
            val root = parseXmlRoot(entries.getValue(path)) ?: continue
            val texts = mutableListOf<String>()
            val aTexts = root.getElementsByTagName("a:t")
            for (i in 0 until aTexts.length) {
                val t = aTexts.item(i).textContent?.trim().orEmpty()
                if (t.isNotEmpty()) texts += t
            }
            if (texts.isNotEmpty()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("--- Slide $num ---\n")
                out.append(texts.joinToString("\n"))
            }
        }
        return out.toString().trim()
    }

    internal fun extractOdfText(bytes: ByteArray): String {
        val entries = readZipEntriesMap(bytes)
        val contentXml = entries["content.xml"] ?: return ""
        val root = parseXmlRoot(contentXml) ?: return ""
        val out = mutableListOf<String>()
        val paras = root.getElementsByTagName("text:p")
        for (i in 0 until paras.length) {
            val t = paras.item(i).textContent?.trim().orEmpty()
            if (t.isNotEmpty()) out += t
        }
        val headings = root.getElementsByTagName("text:h")
        if (out.isEmpty()) {
            for (i in 0 until headings.length) {
                val t = headings.item(i).textContent?.trim().orEmpty()
                if (t.isNotEmpty()) out += t
            }
        }
        return out.joinToString("\n\n")
    }

    internal fun stripRtf(rtf: String): String {
        // Remove font/color/stylesheet control groups, then strip control words & braces
        val noDestGroups = rtf.replace(Regex("""\{\\\*(?:[^{}]|\{[^{}]*\})*\}"""), "")
            .replace(Regex("""\{\\(?:fonttbl|colortbl|stylesheet|info|pict)[^{}]*(?:\{[^{}]*\}[^{}]*)*\}"""), "")
        val hexDecoded = Regex("""\\'([0-9a-fA-F]{2})""").replace(noDestGroups) { m ->
            val code = m.groupValues[1].toInt(16)
            if (code in 32..126) code.toChar().toString() else " "
        }
        return hexDecoded
            .replace(Regex("""\\par[d]?\b\s?"""), "\n")
            .replace(Regex("""\\line\b\s?"""), "\n")
            .replace(Regex("""\\tab\b\s?"""), "\t")
            .replace(Regex("""\\[a-zA-Z]+-?\d*\s?"""), "")
            .replace("{", "")
            .replace("}", "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    internal fun extractLegacyBinaryText(bytes: ByteArray): String {
        val sb = StringBuilder()
        var current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c == 9 || c == 10 || c == 13) {
                current.append(c.toChar())
            } else {
                if (current.length >= 6) {
                    sb.append(current).append('\n')
                }
                current = StringBuilder()
            }
        }
        if (current.length >= 6) sb.append(current)
        return sb.toString().trim().take(25_000)
    }
}
