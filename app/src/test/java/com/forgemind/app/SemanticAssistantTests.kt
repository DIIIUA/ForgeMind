package com.forgemind.app

import com.forgemind.app.data.memory.*
import com.forgemind.app.data.db.AssistantProfileEntity
import com.forgemind.app.agent.SystemPromptComposer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SemanticAssistantTests {
    private fun tokenizer() = WordPieceTokenizer(javaClass.classLoader!!.getResourceAsStream("semantic-vocab.txt")!!.bufferedReader().readLines())
    @Test fun tokenizerMatchesPinnedHuggingFaceReferenceIncludingRussianAndSpecialTokens() {
        val cases=Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("semantic-tokenizer-cases.json")!!.bufferedReader().readText()).jsonArray
        val tokenizer=tokenizer()
        cases.forEach { row ->
            val obj=row.jsonObject
            assertArrayEquals(obj["text"]!!.jsonPrimitive.content,obj["ids"]!!.jsonArray.map { it.jsonPrimitive.long }.toLongArray(),tokenizer.pieces(obj["text"]!!.jsonPrimitive.content))
        }
    }
    @Test fun longNotesCoverEveryTokenIncludingTail() {
        val tokenizer=tokenizer();val text=("Погода хорошая. ").repeat(100)+"хвост"
        val pieces=tokenizer.pieces(text);val chunks=tokenizer.chunks(text)
        assertTrue(chunks.size>1);assertTrue(chunks.all { it.size<=128 })
        val restored=chunks.first().drop(1).dropLast(1)+chunks.drop(1).flatMap { it.drop(17).dropLast(1) }
        assertEquals(pieces.toList(),restored)
    }
    @Test fun emptyTextHasBoundaryTokens() { assertEquals(2,tokenizer().chunks("").single().size) }
    @Test fun cosineUsesNormalizedVectors() {
        val a=SemanticMath.normalize(floatArrayOf(3f,4f));assertEquals(1f,SemanticMath.cosine(a,a),0.0001f)
        assertEquals(-1f,SemanticMath.cosine(a,a.map { -it }.toFloatArray()),0.0001f)
    }
    @Test fun badVectorsAreRejected() {
        assertTrue(runCatching { SemanticMath.normalize(floatArrayOf(Float.NaN)) }.isFailure)
        assertTrue(runCatching { SemanticMath.normalize(floatArrayOf(0f,0f)) }.isFailure)
        assertTrue(runCatching { SemanticMath.cosine(floatArrayOf(1f),floatArrayOf(1f,2f)) }.isFailure)
    }
    @Test fun matchingTailChunkIsNotLostInAnAverage() {
        assertEquals(1f,SemanticMath.score(listOf(floatArrayOf(1f,0f)),listOf(floatArrayOf(0f,1f),floatArrayOf(1f,0f))),0.0001f)
    }
    @Test fun assistantMemoryFollowsProfileNotModel() {
        val a=MemoryContext("a",null,"p","m","assistant")
        val note=MemoryPolicy.note(a,MemoryScope.ASSISTANT,"name","text")
        assertTrue(a.copy(chatId="b",modelId="other").allows(note))
        assertFalse(a.copy(assistantId="other").allows(note));assertFalse(a.copy(assistantId=null).allows(note))
        MemoryPolicy.validateStored(note)
    }
    @Test fun absentProfileCannotCreateAssistantMemory() {
        assertTrue(runCatching { MemoryPolicy.note(MemoryContext("a",null,"p","m"),MemoryScope.ASSISTANT,"n","t") }.isFailure)
    }
    @Test fun historyPrecedenceIncludesUnlimitedAndInheritance() {
        assertEquals(3,AssistantPolicy.historyLimit(3,9,20))
        assertEquals(0,AssistantPolicy.historyLimit(-1,0,20))
        assertEquals(9,AssistantPolicy.historyLimit(-1,9,20))
        assertEquals(20,AssistantPolicy.historyLimit(-1,-1,20))
        assertEquals(20,AssistantPolicy.historyLimit(-1,null,20))
    }
    @Test fun profileValidationRejectsHalfSelectedModel() {
        assertTrue(runCatching { AssistantPolicy.validate(AssistantProfileEntity("id","name",defaultProviderId="p")) }.isFailure)
        assertTrue(runCatching { AssistantPolicy.validate(AssistantProfileEntity("id"," ")) }.isFailure)
        AssistantPolicy.validate(AssistantProfileEntity("id","name",historyMessageLimit=0))
    }
    @Test fun assistantInstructionsAddToSameSystemMessage() {
        val prompt=SystemPromptComposer.compose("BASE_MARKER","MODEL_MARKER","GLOBAL_MARKER","CHAT_MARKER",assistantInstructions="PROFILE_MARKER")
        for(marker in listOf("BASE_MARKER","MODEL_MARKER","GLOBAL_MARKER","CHAT_MARKER","PROFILE_MARKER")) assertTrue(prompt.contains(marker))
        assertTrue(prompt.contains("equal authority"));assertTrue(prompt.indexOf("PROFILE_MARKER")<prompt.indexOf("CHAT_MARKER"))
    }
}
