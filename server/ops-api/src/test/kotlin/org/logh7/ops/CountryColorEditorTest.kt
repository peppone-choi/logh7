package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class CountryColorEditorTest {
    @TempDir lateinit var directory:Path
    @Test fun `operator can preview save reset and detect stale country colors without exposing arbitrary CSS`() {
        var appearance=CountryAppearance(100,"Name <script>","new_empire",CountryColors(),9007199254740993L)
        var fail=false
        val store=object:CountryColorStore {
            override fun list()=listOf(appearance)
            override fun save(countryId:Int,expectedRevision:Long,colors:CountryColors):CountryColorOutcome {
                if(fail)throw java.sql.SQLException("fixture unavailable")
                if(countryId!=appearance.countryId)return CountryColorOutcome.NOT_FOUND
                if(expectedRevision!=appearance.revision)return CountryColorOutcome.CONFLICT
                if(colors==appearance.colors)return CountryColorOutcome.UNCHANGED
                appearance=appearance.copy(colors=colors,revision=appearance.revision+1)
                return CountryColorOutcome.CHANGED
            }
        }
        val source=object:SnapshotSource {override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink {override suspend fun submit(command:WorldCommand)=error("Color metadata does not mutate game state")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-palette-token";val file=directory.resolve("audit.jsonl")
        OpsAudit.open(file).use {audit->startOpsApi(sink,source,port,audit,token,countryColors=store).use {HttpClient.newHttpClient().use {client ->
            fun request(body:String?=null,authorized:Boolean=true,origin:String="http://127.0.0.1:$port"):HttpResponse<String> {
                val path=if(body==null)"/admin/countries?country=100" else "/admin/countries/colors"
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                if(authorized)builder.header("Authorization","Bearer $token")
                if(body==null)builder.GET() else builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            }
            fun form(primary:String="%23123456",revision:Long=appearance.revision)=
                "operator=peppone&country=100&revision=$revision&primary=$primary&secondary=%23FFFFFF&map=%23000000&fleet=inherit"
            assertEquals(401,request(authorized=false).statusCode())
            val initial=request();assertEquals(200,initial.statusCode());assertTrue(initial.body().contains("Name &lt;script&gt;"))
            assertTrue(initial.body().contains("value=\"9007199254740993\""))
            assertTrue(initial.body().contains("○ 아군"));assertTrue(initial.body().contains("◇ 적군"))
            val first=form()
            assertEquals(401,request(first,authorized=false).statusCode())
            assertEquals(403,request(first,origin="https://other.invalid").statusCode())
            assertEquals(400,request(form(primary="url(evil)")).statusCode());assertEquals(CountryColors(),appearance.colors)
            assertEquals(200,request(first).statusCode());assertEquals(CountryColors(0x123456,0xffffff,0,null),appearance.colors)
            assertEquals(9007199254740994L,appearance.revision)
            assertTrue(request().body().contains("value=\"#123456\""))
            assertEquals(409,request(first).statusCode())
            assertEquals("unchanged",request(form()).body())
            assertEquals(200,request(form(primary="inherit")).statusCode());assertNull(appearance.colors.primary)
            val before=appearance;fail=true
            assertEquals(500,request(form()).statusCode());assertEquals(before,appearance)
            val log=Files.readString(file)
            assertTrue(log.contains("primary=#123456,secondary=#FFFFFF,map=#000000,fleet=inherit"))
            for(outcome in listOf("INVALID","CHANGED","CONFLICT","UNCHANGED","FAILED"))assertTrue(log.contains("\"outcome\":\"$outcome\""),outcome)
        }}}
    }
}
