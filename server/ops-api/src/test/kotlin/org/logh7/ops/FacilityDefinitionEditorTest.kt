package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class FacilityDefinitionEditorTest {
    @TempDir lateinit var directory:Path

    private class MemoryStore(var snapshot:FacilityDefinitionSnapshot=FacilityDefinitionDefaults.snapshot()):FacilityDefinitionStore {
        var unavailable=false;var writes=0
        override fun load()=snapshot.also {if(unavailable)throw java.sql.SQLException("fixture unavailable")}
        override fun save(expectedRevision:Long,edit:FacilityDefinitionEdit):FacilityDefinitionOutcome {
            writes++
            if(unavailable)throw java.sql.SQLException("fixture unavailable")
            if(expectedRevision!=snapshot.revision)return FacilityDefinitionOutcome.CONFLICT
            val next=snapshot.applied(edit) ?: return FacilityDefinitionOutcome.INVALID
            if(next===snapshot)return FacilityDefinitionOutcome.UNCHANGED
            snapshot=next;return FacilityDefinitionOutcome.CHANGED
        }
    }

    @Test fun operatorEditsKindsAndOverridesWithLoginOriginConflictAuditAndPublish() {
        val store=MemoryStore();val published=PublishedFacilityDefinitions()
        val source=object:SnapshotSource {override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink {override suspend fun submit(command:WorldCommand)=error("Facility definitions do not edit the world")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-facility-token";val file=directory.resolve("audit.jsonl")
        val commands=FacilityCommandCatalog {kind->if(kind==20)listOf("강의 수강") else emptyList()}
        OpsAudit.open(file).use {audit->startOpsApi(sink,source,port,audit,token,facilityDefinitions=store,publishedFacilities=published,
            facilityCommands=commands).use {HttpClient.newHttpClient().use {client->
            fun request(body:String?=null,authorized:Boolean=true,origin:String="http://127.0.0.1:$port",query:String=""):HttpResponse<String> {
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/facilities$query"))
                if(authorized)builder.header("Authorization","Bearer $token")
                if(body==null)builder.GET() else builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            }
            fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8)
            fun kind(revision:Long=store.snapshot.revision,name:String="신궁전")=
                "entity=kind&operator=peppone&revision=$revision&kind=22&name=${encode(name)}&purpose=${encode("황제의 궁전 <b>")}&usage=${encode("소속 판정을 따른다.")}"
            assertEquals(401,request(authorized=false).statusCode())
            val page=request();assertEquals(200,page.statusCode())
            assertTrue(page.body().contains("황궁") && page.body().contains("미연결") && page.body().contains("강의 수강"),"defaults, unconnected and engine commands are shown")
            val edit=kind();assertEquals(403,request(edit,origin="https://foreign.invalid").statusCode());assertEquals(0,store.writes)
            assertEquals(400,request(kind(name="가".repeat(25))).statusCode())
            assertEquals(200,request(edit).statusCode())
            assertEquals("신궁전",published.current().name(FacilityDefinitionSnapshot.facilityId(1049,22)),"a saved edit is published to the game")
            assertTrue(request(query="?kind=22").body().contains("22 · 신궁전</option>"),"the selector shows the edited facility name")
            assertEquals(409,request(edit).statusCode());assertEquals("unchanged",request(kind()).body())
            assertTrue(request(query="?kind=22").body().contains("황제의 궁전 &lt;b&gt;"),"descriptions are escaped")
            val palace=FacilityDefinitionSnapshot.facilityId(1049,22)
            assertEquals(400,request("entity=override&operator=peppone&revision=${store.snapshot.revision}&facility_id=$palace&name=&purpose=").statusCode(),
                "an override needs a name or a purpose")
            assertEquals(200,request("entity=override&operator=peppone&revision=${store.snapshot.revision}&facility_id=$palace&name=${encode("노이에 상수시")}&purpose=").statusCode())
            assertEquals("노이에 상수시",published.current().displayName(palace))
            assertTrue(request().body().contains("노이에 상수시"))
            assertEquals(200,request("entity=clear&operator=peppone&revision=${store.snapshot.revision}&facility_id=$palace").statusCode())
            assertEquals("신궁전",published.current().displayName(palace))
            store.unavailable=true
            val down=request();assertEquals(200,down.statusCode());assertTrue(down.body().contains("DB를 읽지 못했습니다") && down.body().contains("신궁전"),
                "a DB failure shows the last published definitions read-only")
            assertEquals(500,request(kind(name="다른 이름")).statusCode())
            val log=Files.readString(file)
            assertTrue(log.contains("FACILITY_DEFINITION_EDIT") && log.contains("kind=22") && log.contains("clear=$palace"))
            for(outcome in listOf("INVALID","CHANGED","CONFLICT","UNCHANGED","FAILED"))assertTrue(log.contains("\"outcome\":\"$outcome\""))
        }}}
        val readOnlyPort=ServerSocket(0).use {it.localPort}
        startOpsApi(sink,source,readOnlyPort).use {HttpClient.newHttpClient().use {client->
            val page=client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$readOnlyPort/admin/facilities")).build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(200,page.statusCode());assertTrue(page.body().contains("읽기 전용") && !page.body().contains("종류 정의 저장"))
            val post=HttpRequest.newBuilder(URI("http://127.0.0.1:$readOnlyPort/admin/facilities")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("entity=kind&operator=peppone&revision=0&kind=3&name=x&purpose=&usage=")).build()
            assertEquals(503,client.send(post,HttpResponse.BodyHandlers.ofString()).statusCode(),"without an audit log nothing is written")
        }}
    }
}
