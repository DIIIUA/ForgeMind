package com.forgemind.app

import com.forgemind.app.agent.*
import com.forgemind.app.ui.ChatTerminalCommand
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TextToolGuardTests {
    private val names = setOf("run_command", "list_files")
    private val call = """<tool_call>{"name":"run_command","arguments":{"command":"printf SAFE"}}</tool_call>"""
    private fun parse(text: String) = TextToolProtocol.parse(text, names)
    private fun inert(text: String) { val parsed = parse(text); assertTrue(parsed.toolCalls.isEmpty()); assertEquals(text, parsed.cleanedText) }

    @Test fun explicitClosedXmlExecutesOnceAndIsRemovedFromAnswer() {
        val parsed = parse("Starting\n$call\nDone")
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("printf SAFE", Json.parseToJsonElement(parsed.toolCalls.single().argumentsJson).jsonObject["command"]!!.jsonPrimitive.content)
        assertTrue(parsed.toolCalls.single().viaTextFallback)
        assertEquals("Starting\n\nDone", parsed.cleanedText)
    }
    @Test fun fencedAndUnclosedExamplesRemainByteForByte() {
        listOf("```xml\n$call\n```", "~~~json\n$call\n~~~", "````xml\n```\n$call\n````", "```xml\n$call").forEach(::inert)
    }
    @Test fun inlineQuotesIndentedCodeAndBareCodeAreInert() {
        listOf("`$call`", "``$call``", "> $call", "    $call", "\t$call", "Example $call", "run_command(command='pwd')", """{"name":"run_command","arguments":{"command":"pwd"}}""").forEach(::inert)
    }
    @Test fun codeExampleDoesNotMaskFollowingRealCall() {
        val example = "```xml\n$call\n```"
        val parsed = parse("$example\n$call")
        assertEquals(1, parsed.toolCalls.size); assertEquals(example, parsed.cleanedText)
    }
    @Test fun strictJsonRequiresObjectArgumentsAndAvailableName() {
        listOf(call.removeSuffix("</tool_call>"), call.replace("SAFE\"}", "SAFE\",}"),
            "<tool_call>{'name':'run_command','arguments':{}}</tool_call>",
            """<tool_call>{"name":"run_command","arguments":"pwd"}</tool_call>""",
            call.replace("run_command", "unavailable_tool")).forEach(::inert)
        assertTrue(TextToolProtocol.parse(call, emptySet()).toolCalls.isEmpty())
    }
    @Test fun prettyJsonAndShellBackticksAreNotModified() {
        val text = "<tool_call>\n    {\"name\":\"run_command\",\"arguments\":{\"command\":\"echo `pwd`\"}}\n</tool_call>"
        assertEquals("echo `pwd`", Json.parseToJsonElement(parse(text).toolCalls.single().argumentsJson).jsonObject["command"]!!.jsonPrimitive.content)
    }
    @Test fun streamingKeepsCodeExamplesVisible() {
        val text = "```xml\n$call\n```\n`<tool_call>`"
        assertEquals(text, AgentEngine.stripLiveDsmlPreview(text))
    }
    @Test fun shellExamplesNeverTriggerExecutionNudge() {
        assertFalse(AgentEngine.shouldNudgeUnexecutedCommandOutput("Run it", "```bash\nrm example\n```", true))
        assertFalse(AgentEngine.shouldNudgeUnexecutedCommandOutput("Explain XML", call, true))
    }
    @Test fun directCommandsRequireExplicitPrefixOrMode() {
        assertEquals("pwd", ChatTerminalCommand.extract("/term pwd"))
        assertEquals("echo one\necho two", ChatTerminalCommand.extract("/term\necho one\necho two"))
        assertEquals("pwd", ChatTerminalCommand.extract("pwd", true))
        listOf("pwd", "    /term pwd", "\t/term pwd", "```sh\n/term pwd\n```", "`/term pwd`", "> /term pwd", "/terminal pwd", "Example: /term pwd").forEach { assertNull(ChatTerminalCommand.extract(it)) }
    }
}
