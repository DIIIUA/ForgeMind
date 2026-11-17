package com.forgemind.app

import com.forgemind.app.agent.ToolResultFormatter
import com.forgemind.app.agent.ToolRunOutput
import com.forgemind.app.bridge.TolerantEdit
import com.forgemind.app.bridge.UnifiedPatch
import com.forgemind.app.voice.SttHallucinationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Agent-computer interface: file editing for the agent, tool-result contract, STT cleanup. */
class AgentInterfaceTests {

    // ---------- UnifiedPatch ----------

    private val source = listOf(
        "fun a() {",
        "    val x = 1",
        "    println(x)",
        "}",
        "",
        "fun b() {",
        "    val y = 2",
        "    println(y)",
        "}"
    ).joinToString("\n") + "\n"

    @Test
    fun `patch applies two hunks and keeps the trailing newline`() {
        val diff = """
            --- a/Main.kt
            +++ b/Main.kt
            @@ -1,3 +1,3 @@
             fun a() {
            -    val x = 1
            +    val x = 10
                 println(x)
            @@ -6,3 +6,4 @@
             fun b() {
                 val y = 2
            +    val z = 3
                 println(y)
        """.trimIndent()
        val out = UnifiedPatch.apply(source, diff)
        assertEquals(2, out.hunks)
        assertTrue(out.text.contains("val x = 10"))
        assertTrue(out.text.contains("    val y = 2\n    val z = 3\n    println(y)"))
        assertTrue(out.text.endsWith("}\n"))
    }

    @Test
    fun `patch finds a shifted hunk and preserves CRLF`() {
        val crlf = ("// header line\n" + source).replace("\n", "\r\n")
        val diff = "@@ -2,2 +2,2 @@\n     val y = 2\n-    println(y)\n+    println(\"y=\" + y)\n"
        val out = UnifiedPatch.apply(crlf, diff)
        assertTrue(out.text.contains("println(\"y=\" + y)\r\n"))
        assertTrue(out.notes.any { it.contains("applied at line") })
        assertTrue(!out.text.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `patch tolerates indentation drift`() {
        val diff = "@@ -2,1 +2,1 @@\n-  val x = 1\n+    val x = 2\n"
        val out = UnifiedPatch.apply(source, diff)
        assertTrue(out.text.contains("    val x = 2"))
        assertTrue(out.notes.any { it.contains("ignoring indentation") })
    }

    @Test
    fun `patch that does not match explains what the file has`() {
        try {
            UnifiedPatch.apply(source, "@@ -2,1 +2,1 @@\n-    val q = 99\n+    val q = 100\n")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("does not match"))
            assertTrue(e.message!!.contains("Re-read the file"))
        }
    }

    @Test
    fun `patch refuses multi-file diffs`() {
        val diff = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-a\n+b\n"
        try {
            UnifiedPatch.apply("a\n", diff)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("once per file"))
        }
    }

    // ---------- TolerantEdit ----------

    @Test
    fun `edit matches LF snippet in a CRLF file`() {
        val crlf = source.replace("\n", "\r\n")
        val out = TolerantEdit.apply(crlf, "    val x = 1\n    println(x)", "    val x = 5\n    println(x)", "Main.kt")
        assertTrue(out.updated.contains("    val x = 5\r\n    println(x)"))
        assertTrue(out.note.contains("CRLF"))
    }

    @Test
    fun `edit ignores indentation when unique`() {
        val out = TolerantEdit.apply(source, "val y = 2\nprintln(y)", "    val y = 20\n    println(y)", "Main.kt")
        assertTrue(out.updated.contains("    val y = 20"))
        assertTrue(out.note.contains("ignoring indentation"))
    }

    @Test
    fun `edit failure shows the closest fragment with line numbers`() {
        try {
            TolerantEdit.apply(source, "    println(xx)", "z", "Main.kt")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Closest fragment"))
            assertTrue(e.message!!.contains("| "))
        }
    }

    // ---------- ToolResultFormatter ----------

    @Test
    fun `empty success is explicit and long output says it was cut`() {
        assertTrue(ToolResultFormatter.forModel("run_command", ToolRunOutput("")).contains("вывода нет"))
        val long = "x".repeat(ToolResultFormatter.MAX_TOOL_RESULT_CHARS + 500)
        val text = ToolResultFormatter.forModel("run_command", ToolRunOutput(long))
        assertTrue(text.contains("ОБРЕЗАНО"))
        assertTrue(text.contains("${ToolResultFormatter.MAX_TOOL_RESULT_CHARS + 500}"))
    }

    @Test
    fun `errors carry a fix block with parameters`() {
        val text = ToolResultFormatter.forModel("file_read", ToolRunOutput("Отсутствует path", isError = true), "path (обязательный), offset_line?")
        assertTrue(text.contains(ToolResultFormatter.FIX_MARKER))
        assertTrue(text.contains("path (обязательный)"))
    }

    @Test
    fun `unknown tool gets close real names`() {
        val names = ToolResultFormatter.closestNames("web_downlod", listOf("web_download", "web_scrape", "file_read", "run_command"))
        assertEquals("web_download", names.first())
    }

    // ---------- STT ----------

    @Test
    fun `whisper silence hallucinations are dropped`() {
        assertEquals("", SttHallucinationFilter.clean("Продолжение следует..."))
        assertEquals("", SttHallucinationFilter.clean("Субтитры сделал DimaTorzok"))
        assertEquals("открой браузер", SttHallucinationFilter.clean("открой браузер. Продолжение следует..."))
        assertEquals("продолжение работы над задачей", SttHallucinationFilter.clean("продолжение работы над задачей"))
    }
}
