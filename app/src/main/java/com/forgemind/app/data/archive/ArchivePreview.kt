package com.forgemind.app.data.archive

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

data class ArchiveChange(val kind: String, val id: String, val action: String, val before: String, val after: String)
data class ArchivePreview(val file: File, val digest: String, val baseline: String, val changes: List<ArchiveChange>,
    val sessions: Int, val messages: Int, val attachments: Int, val preservedSessions: Int)

object ArchivePreviewReader {
    const val MAX_COMPRESSED = 256L * 1024 * 1024
    const val MAX_EXPANDED = 512L * 1024 * 1024
    fun hash(file: File): String = file.inputStream().use { stream ->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun copyBounded(input: InputStream, file: File) {
        file.outputStream().use { out ->
            val buffer=ByteArray(65536);var total=0L
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=MAX_COMPRESSED){"Архив больше 256 МБ"};out.write(buffer,0,n)}
        }
    }
    fun inspect(input: InputStream): Pair<ChatArchiveBundle,Int> {
        var manifest: String?=null;var count=0;var expanded=0L;var files=0
        val names=mutableSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(++count<=20000){"Слишком много файлов"}
                val name=entry.name.replace('\\','/')
                require(!name.startsWith('/') && !name.contains(':') && name.split('/').none{it==".."}){"Небезопасный путь в архиве"}
                require(names.add(name)){"Повторное имя файла в архиве"}
                if (!entry.isDirectory) {
                    val bytes=if(name=="manifest.json") java.io.ByteArrayOutputStream() else null
                    val buffer=ByteArray(65536)
                    while(true) {
                        val n=zip.read(buffer);if(n<0)break;expanded+=n
                        require(expanded<=MAX_EXPANDED){"Распакованный архив больше 512 МБ"}
                        if(bytes!=null){require(bytes.size()+n<=32*1024*1024){"Манифест слишком велик"};bytes.write(buffer,0,n)}
                    }
                    if(bytes!=null)manifest=bytes.toString("UTF-8")
                    if(name.startsWith("attachments/"))files++
                }
                zip.closeEntry()
            }
        }
        val bundle=Json{ignoreUnknownKeys=true}.decodeFromString<ChatArchiveBundle>(manifest ?: error("Нет manifest.json"))
        require(bundle.formatVersion in 1..5){"Неподдерживаемая версия архива"}
        require(bundle.sessions.map{it.id}.distinct().size==bundle.sessions.size){"Повторные ID чатов"}
        require(bundle.messages.map{it.id}.distinct().size==bundle.messages.size){"Повторные ID сообщений"}
        val sessions=bundle.sessions.map{it.id}.toSet()
        require(bundle.messages.all{it.sessionId in sessions}){"Сообщение без чата"}
        bundle.assistants.forEach(com.forgemind.app.data.memory.AssistantPolicy::validate)
        require(bundle.schedules.size<=100 && bundle.schedules.distinctBy{it.id}.size==bundle.schedules.size) { "Некорректные расписания" }
        bundle.schedules.forEach { require(it.sessionId in sessions); com.forgemind.app.scheduling.SchedulePolicy.validate(it) }
        return bundle to files
    }
}

object ArchiveComparison {
    fun compare(current: ChatArchiveBundle, incoming: ChatArchiveBundle): List<ArchiveChange> {
        val changes=mutableListOf<ArchiveChange>()
        fun <T> compareKind(kind:String,old:List<T>,next:List<T>,id:(T)->String,label:(T)->String,keepExisting:Boolean=false) {
            val lookup=old.associateBy(id)
            next.forEach { value ->
                val previous=lookup[id(value)]
                val action=when{previous==null->"Добавить";previous==value->"Без изменений";keepExisting->"Сохранить текущую запись";else->"Заменить"}
                changes+=ArchiveChange(kind,id(value),action,previous?.let(label).orEmpty(),label(value))
            }
        }
        compareKind("Чат",current.sessions,incoming.sessions,{it.id},{it.title})
        compareKind("Сообщение",current.messages,incoming.messages,{it.id},{it.content.take(240)})
        compareKind("Папка",current.folders,incoming.folders,{it.id},{it.name})
        compareKind("Ассистент",current.assistants,incoming.assistants,{it.id},{it.name},true)
        compareKind("Память",current.memoryNotes,incoming.memoryNotes,{it.id},{it.title},true)
        compareKind("Расписание (импорт отключённым)",current.schedules,incoming.schedules,{it.id},{it.name},true)
        return changes
    }
}
