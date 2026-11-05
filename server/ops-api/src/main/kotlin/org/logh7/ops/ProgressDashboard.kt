package org.logh7.ops

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** Counts document markers; it does not infer game readiness. Author: Peppone Choi (peppone-choi). */
data class ProgressSection(val title:String,val completed:Int,val total:Int)
data class ChecklistProgress(val sections:List<ProgressSection>) {
    val completed=sections.sumOf {it.completed}
    val total=sections.sumOf {it.total}
    val percent:Double get()=completed*100.0/total
    init {require(total>0 && sections.all {it.completed in 0..it.total}) {"No valid checklist items"}}
    companion object {
        fun parse(text:String):ChecklistProgress {
            val counts=linkedMapOf<String,IntArray>()
            var section="기타"
            var fence:String?=null
            val task=Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+.+$")
            for(line in text.removePrefix("\uFEFF").lineSequence()) {
                val trimmed=line.trimStart()
                val marker=when {trimmed.startsWith("```")->"```";trimmed.startsWith("~~~")->"~~~";else->null}
                if(marker!=null){if(fence==null)fence=marker else if(fence==marker)fence=null;continue}
                if(fence!=null)continue
                if(line.startsWith("## "))section=line.substring(3).trim()
                val match=task.matchEntire(line) ?: continue
                val count=counts.getOrPut(section){intArrayOf(0,0)}
                count[1]++;if(match.groupValues[1].equals("x",ignoreCase=true))count[0]++
            }
            require(fence==null) {"Unclosed checklist code block"}
            return ChecklistProgress(counts.map {(title,count)->ProgressSection(title,count[0],count[1])})
        }
    }
}

private fun jsonString(value:String):String=buildString {
    append('"')
    for(c in value)when(c){'"'->append("\\\"");'\\'->append("\\\\");'\n'->append("\\n");'\r'->append("\\r");'\t'->append("\\t")
        else->if(c.code<32)append("\\u%04x".format(c.code)) else append(c)}
    append('"')
}

internal fun progressJson(path:Path):String {
    require(Files.size(path)<=2_097_152) {"Checklist is too large"}
    val before=Files.getLastModifiedTime(path)
    val bytes=Files.readAllBytes(path)
    check(before==Files.getLastModifiedTime(path)) {"Checklist is being edited"}
    val progress=ChecklistProgress.parse(bytes.toString(Charsets.UTF_8))
    val fingerprint=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    val sections=progress.sections.joinToString(",") {"""{"title":${jsonString(it.title)},"completed":${it.completed},"total":${it.total}}"""}
    return """{"completed":${progress.completed},"total":${progress.total},"percent":${progress.percent},"generatedAt":"${Instant.now()}","source":${jsonString(path.fileName.toString())},"fingerprint":"$fingerprint","sections":[$sections]}"""
}

internal fun Route.progressRoutes(checklist:Path?,pagePath:String,dataPath:String) {
    get(pagePath) {
        val html=checkNotNull(ChecklistProgress::class.java.getResourceAsStream("/admin/progress.html"))
            .bufferedReader(Charsets.UTF_8).use {it.readText()}.replace("__PROGRESS_API__",dataPath)
        call.respondText(html,ContentType.Text.Html)
    }
    get(dataPath) {
        val json=try {withContext(Dispatchers.IO){progressJson(checkNotNull(checklist))}}
            catch(_:java.io.IOException){null} catch(_:IllegalArgumentException){null} catch(_:IllegalStateException){null}
        call.response.headers.append("Cache-Control","no-store")
        if(json==null)call.respondText("checklist unavailable",status=HttpStatusCode.ServiceUnavailable)
        else call.respondText(json,ContentType.Application.Json)
    }
}

/** A local read-only project dashboard; it starts no game engine and reads no env secrets. */
fun main(args:Array<String>) {
    val port=args.getOrNull(0)?.toInt() ?: 48093
    require(port in 1..65535)
    val checklist=Path.of(args.getOrNull(1) ?: "docs/IMPLEMENTATION-CHECKLIST.md").toAbsolutePath()
    progressJson(checklist)
    embeddedServer(Netty,host="127.0.0.1",port=port) {routing {progressRoutes(checklist,"/","/api/progress")}}.start(wait=true)
}
