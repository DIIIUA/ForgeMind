package com.forgemind.app.voice

/** Hidden code/protocol blocks stay hidden across arbitrary stream deltas. */
class StreamingSpeechText {
    private val pending = StringBuilder()
    private var fence: String? = null
    private var toolBlock = false
    fun append(delta: String, finish: Boolean = false): List<String> {
        pending.append(delta)
        val out = mutableListOf<String>()
        fun emit(raw: String) { cleanSpeechMarkdown(raw).takeIf { it.isNotBlank() }?.let(out::add) }
        while (pending.isNotEmpty()) {
            if (fence != null || toolBlock) {
                val close = if (toolBlock) Regex("(?i)</tool_?calls?\\s*>").find(pending)
                    else Regex("(?m)^\\s*${Regex.escape(fence!!)}[^\\n]*(?:\\n|$)").find(pending)
                if (close != null) {
                    pending.delete(0, close.range.last+1); fence=null; toolBlock=false; continue
                }
                if (finish) pending.clear()
                else if (pending.length>8192) pending.delete(0,pending.length-64)
                break
            }
            val marker=Regex("(?im)^\\s*(```|~~~)|<tool_?calls?\\b[^>]*>").find(pending)
            val boundary=Regex("[.!?。！？](?=\\s|$)|\\n").find(pending)?.range?.first ?: -1
            if (marker!=null && (boundary<0 || marker.range.first<=boundary)) {
                emit(pending.substring(0,marker.range.first))
                val token=marker.value.trimStart()
                pending.delete(0,marker.range.last+1)
                if (token.startsWith("<")) toolBlock=true else fence=token.take(3)
                continue
            }
            val uncertain=pending.indexOf("<").takeIf { it>=0 && pending.indexOf(">",it)<0 }
            val end=when {
                boundary>=0 && (uncertain==null || boundary<uncertain) -> boundary+1
                finish -> pending.length
                pending.length>8192 && uncertain==null -> pending.lastIndexOf(" ",4096).takeIf { it>0 } ?: 4096
                else -> -1
            }
            if (end<0) {
                if (pending.length>8192 && uncertain!=null && uncertain<pending.length-64) pending.delete(uncertain,pending.length-64)
                break
            }
            emit(pending.substring(0,end));pending.delete(0,end)
        }
        return out
    }
}

fun cleanSpeechMarkdown(raw: String): String = raw
    .replace(Regex("(?is)<tool_?calls?\\b[^>]*>.*?(?:</tool_?calls?>|$)"), " ")
    .replace(Regex("(?s)```.*?(?:```|$)"), " ")
    .replace(Regex("(?s)~~~.*?(?:~~~|$)"), " ")
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s*(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)"), "")
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("https?://\\S+"), " ")
    .replace(Regex("[`*_~|]"), " ")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ").trim()
