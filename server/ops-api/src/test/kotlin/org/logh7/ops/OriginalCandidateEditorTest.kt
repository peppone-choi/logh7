package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class OriginalCandidateEditorTest {
    @TempDir lateinit var directory:Path

    private class Store:OriginalCandidateStore {
        var office=true
        var allianceOffice=false
        val saved=mutableMapOf<Long,OriginalCandidateEdit>()
        override val supportsStartingOffice get()=office
        override val supportsAllianceStartingOffice get()=allianceOffice
        override fun load()=saved.values.toList()
        override fun save(id:Long,expected:Long,profile:OriginalCandidateProfile,country:CountryId?):OriginalCandidateEdit? {
            if(expected!=(saved[id]?.revision ?: 0))return null
            return OriginalCandidateEdit(id,expected+1,profile,country).also {saved[id]=it}
        }
    }

    @Test fun allianceVucockOfficeFollowsV17CapabilityAndStaysBound() {
        val store=Store()
        val abilities=List(8){50}
        val published=PublishedOriginalCandidates(listOf(
            OriginalCandidateDefinition(1,2,OriginalCandidateProfile("제국","후보",1,13,abilities)),
            OriginalCandidateDefinition(2,3,OriginalCandidateProfile("동맹","후보",100001,13,abilities))))
        val source=object:SnapshotSource{override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink{override suspend fun submit(command:WorldCommand)=error("Candidate edits never queue world commands")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-candidate-token"
        OpsAudit.open(directory.resolve("audit.jsonl")).use {audit->startOpsApi(sink,source,port,audit,token,
            originalCandidateStore=store,originalCandidates=published).use {HttpClient.newHttpClient().use {client->
            fun request(path:String,values:Map<String,String>?=null):HttpResponse<String> {
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Authorization","Bearer $token")
                if(values==null)builder.GET() else {
                    val body=values.entries.joinToString("&"){URLEncoder.encode(it.key,Charsets.UTF_8)+"="+URLEncoder.encode(it.value,Charsets.UTF_8)}
                    builder.header("Origin","http://127.0.0.1:$port").header("Content-Type","application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                }
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            }
            fun page(query:String)=request("/admin/original-candidates?$query").body()
            val vucock=OriginalStartingOffice.VUCOCK_FLEET_5
            val form=mapOf("operator" to "peppone","candidate" to "1003","revision" to "0","create" to "1","country" to "3",
                "surname" to "뷰코크","given" to "알렉산드르","face" to "100001","rank" to "5","peerage" to "0",
                "startingOffice" to vucock.key)+abilities.indices.associate {"ability$it" to "60"}

            // V16 only: the imperial office stays selectable, the alliance office asks for V17 and refuses to save.
            assertTrue(page("new=2").contains("value=\"${OriginalStartingOffice.REINHARD_FLEET_2.key}\""))
            val v16=page("new=3")
            assertTrue(v16.contains("정의 DB V17을 적용"));assertFalse(v16.contains("name=\"startingOffice\""))
            assertEquals(503,request("/admin/original-candidates",form).statusCode());assertTrue(store.saved.isEmpty())
            store.office=false
            assertTrue(page("new=3").contains("정의 DB V16·V17을 적용"));assertTrue(page("new=2").contains("정의 DB V16을 적용"))

            // V17: the alliance form offers Vucock only for country 3 at rank 5 and the same checks reject other routes.
            store.office=true;store.allianceOffice=true
            val v17=page("new=3")
            assertTrue(v17.contains("value=\"${vucock.key}\""));assertTrue(v17.contains("뷰코크 · 제5함대 사령관 (산타 아나)"))
            assertTrue(v17.contains("data-country=\"3\" data-rank=\"5\""));assertFalse(v17.contains(OriginalStartingOffice.REINHARD_FLEET_2.key))
            assertFalse(page("new=2").contains(vucock.key))
            assertEquals(400,request("/admin/original-candidates",form+("rank" to "4")).statusCode())
            assertEquals(400,request("/admin/original-candidates",form+mapOf("country" to "2","face" to "1")).statusCode())
            assertEquals(400,request("/admin/original-candidates",form+mapOf("candidate" to "2","create" to "0")).statusCode())
            assertTrue(store.saved.isEmpty())
            assertEquals(200,request("/admin/original-candidates",form).statusCode())
            assertEquals(vucock,store.saved.getValue(1003).profile.startingOffice)

            // Once published the office is shown as bound and cannot be cleared, while other edits keep it.
            val bound=page("candidate=1003")
            assertTrue(bound.contains("<strong>뷰코크 · 제5함대 사령관 (산타 아나)</strong>"));assertFalse(bound.contains("name=\"startingOffice\""))
            val edit=form-"create"-"startingOffice"+("revision" to "1")
            assertEquals(400,request("/admin/original-candidates",edit+("startingOffice" to "")).statusCode())
            assertEquals(200,request("/admin/original-candidates",edit+("given" to "시드니")).statusCode())
            assertEquals(vucock,store.saved.getValue(1003).profile.startingOffice)
            assertTrue(page("new=3").contains("후보 1003에 이미 지정"))
        }}}
    }
}
