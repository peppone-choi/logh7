package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class WorldEditorTest {
    @TempDir lateinit var directory:Path
    @Test fun `authorized edits commit through actor and stale or failed changes never overwrite inventory`()=runBlocking {
        val home=GalaxyBase(1001,1000,80,0,"Home",0,20f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,1000,4,"Home",2)),listOf(home),Power.entries.associateWith {1000})
        var stored=PlanetStocks(1,0,mapOf(1001 to PlanetStock(1001,2,0,100,200,300)),deploymentInitialized=true)
        var players=emptyMap<String,Navigator>();var fail=false;var commits=0
        val stockStore=object:PlanetStockStore {override fun load()=stored;override fun save(stocks:PlanetStocks){stored=stocks}}
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=stockStore,
            worldStateCommitter=WorldStateCommitter {nav,stock->if(fail)throw IOException("fixture disk failure");commits++;players=nav;stored=checkNotNull(stock)},
            npcPolicy=NpcTacticalPolicy(enabled=false))
        val port=ServerSocket(0).use {it.localPort};val token="fixture-world-token"
        val file=directory.resolve("audit.jsonl")
        try {
            engine.synchronizeTime();commits=0
            OpsAudit.open(file).use {audit->startOpsApi(engine,engine,port,audit,token,galaxy=galaxy).use {HttpClient.newHttpClient().use {client->
            fun request(path:String,body:String?=null,authorized:Boolean=true,origin:String="http://127.0.0.1:$port"):HttpResponse<String> {
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                if(authorized)builder.header("Authorization","Bearer $token")
                if(body==null)builder.GET() else builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            }
            fun edit(version:String=AdminWorldEditing.version(1,stored.bases.getValue(1001)),supplies:Int=123,treasury:String="9007199254740993")=
                "operator=peppone&generation=1&base=1001&version=$version&supplies=$supplies&food=234&mineral=345&treasury=$treasury"
            assertEquals(200,request("/admin/world").statusCode())
            val original=edit()
            assertEquals(401,request("/admin/world",authorized=false).statusCode())
            assertEquals(401,request("/admin/world/base",original,authorized=false).statusCode())
            assertEquals(403,request("/admin/world/base",original,origin="https://other.invalid").statusCode());assertEquals(0,commits)
            assertEquals(200,request("/admin/world/base",original).statusCode());assertEquals(1,commits)
            assertEquals(123,stored.bases.getValue(1001).supplies)
            assertEquals(9007199254740993L,stored.bases.getValue(1001).treasury)
            val live=request("/admin/world/live?base=1001")
            assertEquals(200,live.statusCode());assertTrue(live.body().contains("\"treasury\":\"9007199254740993\""))
            assertEquals(409,request("/admin/world/base",original).statusCode());assertEquals(1,commits)
            assertEquals(400,request("/admin/world/base",edit(treasury="9223372036854775808")).statusCode())
            assertEquals(400,request("/admin/world/base",edit(supplies=-1)).statusCode())
            assertEquals("unchanged",request("/admin/world/base",edit()).body());assertEquals(1,commits)
            val stale=edit()
            val people="&population=12345&adults=6789&approval=73&peace=81"
            assertEquals(200,request("/admin/world/base",edit()+people).statusCode());assertEquals(2,commits)
            assertEquals(PlanetEconomy(12345,6789,73,81),stored.bases.getValue(1001).economy)
            assertTrue(request("/admin/world/live?base=1001").body().contains("\"economy\":{\"population\":12345,\"adults\":6789,\"approval\":73,\"peace\":81}"))
            val page=request("/admin/world").body()
            assertTrue(page.contains("name=\"population\""));assertTrue(page.contains("민심 (%)"));assertTrue(page.contains("치안 (%)"))
            val demographic=stored
            for(invalid in listOf("&population=12345",people.replace("adults=6789","adults=12346"),
                people.replace("population=12345","population=2147483648"),people.replace("adults=6789","adults=-1"),
                people.replace("approval=73","approval=101"),people.replace("peace=81","peace=-1"),
                people.replace("approval=73","approval=73.5"))) {
                val refused=request("/admin/world/base",edit()+invalid)
                assertEquals(400,refused.statusCode());assertTrue(refused.body().contains("값을 확인하세요"))
                assertEquals(demographic,stored);assertEquals(2,commits)
            }
            assertEquals(409,request("/admin/world/base",stale+people).statusCode())
            assertEquals("unchanged",request("/admin/world/base",edit()).body());assertEquals(demographic,stored)
            val before=stored;fail=true
            assertEquals(500,request("/admin/world/base",edit(supplies=999)+people.replace("population=12345","population=20000")).statusCode())
            assertEquals(before,stored);assertEquals(before,engine.snapshot().planetStocks)
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health());assertEquals(2,commits)
            assertEquals(503,request("/admin/world/base",edit(supplies=999)).statusCode())
            val log=Files.readString(file)
            assertTrue(log.contains("base=1001,generation=1,supplies=123"))
            assertTrue(log.contains("population=12345,adults=6789,approval=73,peace=81"))
            for(outcome in listOf("CHANGED","CONFLICT","INVALID","UNCHANGED","FAILED","UNAVAILABLE"))assertTrue(log.contains("\"outcome\":\"$outcome\""),outcome)
        }}}} finally {withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
}
