package org.logh7.ops

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.*

class OpsHealthTest {
    private val snapshot = WorldSnapshot(7, 3)
    private fun snapshots(health: EngineHealth) = object : SnapshotSource {
        override fun snapshot() = snapshot
        override fun health() = health
    }
    private val unusedCommands = object : CommandSink {
        override suspend fun submit(command: WorldCommand) { error("GET must not submit commands") }
    }

    private class Endpoint(private val port: Int) : AutoCloseable {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        override fun close() { client.close() }
    }

    private fun serve(snapshots: SnapshotSource, commands: CommandSink = unusedCommands, test: (Endpoint) -> Unit) {
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        // No audit and no operator headers: health and snapshot remain read-only endpoints.
        startOpsApi(commands, snapshots, port).use { Endpoint(port).use(test) }
    }

    @Test fun runningReturnsUnchangedOk() {
        serve(snapshots(EngineHealth.RUNNING)) { endpoint ->
            val response = endpoint.get("/health")
            assertEquals(200, response.statusCode())
            assertEquals("ok", response.body())
        }
    }

    @Test fun unavailableStatesReturnDistinct503Bodies() {
        for ((state, body) in listOf(EngineHealth.STARTING to "engine starting",EngineHealth.STORAGE_FAILED to "engine storage failed",
            EngineHealth.STOPPED to "engine stopped")) {
            serve(snapshots(state)) { endpoint ->
                val response = endpoint.get("/health")
                assertEquals(503, response.statusCode(), state.name)
                assertEquals(body, response.body())
            }
        }
    }

    @Test fun metricsExposeHealthAndCountersInPrometheusText() {
        serve(snapshots(EngineHealth.STORAGE_FAILED)) { endpoint ->
            val response = endpoint.get("/metrics")
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").get().startsWith("text/plain"))
            val values = response.body().lines().filter { it.startsWith("logh7_") }.associate { it.substringBefore(' ') to it.substringAfter(' ') }
            assertEquals("0", values["logh7_engine_up"]);assertEquals("7", values["logh7_world_revision"])
            assertEquals("3", values["logh7_commands_accepted_total"]);assertEquals("0", values["logh7_session_online"])
            // 14 engine/JVM values plus the process start time used by the backup staleness alert.
            assertEquals(15, values.size);assertTrue(values.getValue("logh7_jvm_heap_used_bytes").toLong()>0)
            assertTrue(values.getValue("logh7_process_start_seconds").toLong()>1_700_000_000L)
        }
    }
    @Test fun adminPageIsReadOnlyHtmlAndEscapesValues() {
        val page = opsAdminPage(EngineHealth.RUNNING, WorldSnapshot(7, 3))
        assertTrue(page.startsWith("<!doctype html>") && "RUNNING" in page && "<td>7</td>" in page)
        serve(snapshots(EngineHealth.RUNNING)) { endpoint ->
            val response = endpoint.get("/admin")
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").get().startsWith("text/html"))
        }
    }
    @Test fun snapshotSourceDefaultHealthKeepsExistingFakesRunning() {
        val source = object : SnapshotSource { override fun snapshot() = snapshot }
        serve(source) { endpoint ->
            val response = endpoint.get("/health")
            assertEquals(200, response.statusCode())
            assertEquals("ok", response.body())
        }
    }

    @Test fun stoppedEngineDoesNotChangeSnapshotContract() {
        serve(snapshots(EngineHealth.STOPPED)) { endpoint ->
            val response = endpoint.get("/snapshot")
            assertEquals(200, response.statusCode())
            assertEquals(snapshot.toString(), response.body())
        }
    }

    @Test fun realEngineCommitFailureBecomesHttp503WithoutPostOrAudit() = runBlocking {
        val rules = SessionRules.load()
        val store = object : SessionDurability {
            override fun load() = DurableWorldState(SessionSimulation(rules).exportState())
            override fun commit(action: SessionAction, transition: SessionTransition) { throw IOException("Injected commit failure") }
            override fun close() {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = WorldEngine(scope, rules, ticking = false, durability = store)
            try {
                engine.submit(WorldCommand.Probe)
                withTimeout(5000) { while (engine.health() != EngineHealth.STORAGE_FAILED) delay(1) }
                serve(engine, engine) { endpoint ->
                    val response = endpoint.get("/health")
                    assertEquals(503, response.statusCode())
                    assertEquals("engine storage failed", response.body())
                }
            } finally { engine.close() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun restoredWorldIsUnavailableUntilItsInventoryAndNavigationArePublished() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,800,700,1000,baseId=1049,mode=4))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,treasury=52000)),
            deploymentInitialized=true,nationalTreasuries=mapOf(CountryId(2) to 450000L))
        val navigation=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) {}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {}
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory)
        try {
            assertNull(engine.snapshot().planetStocks)
            assertEquals(EngineHealth.STARTING,engine.health())
            serve(engine,engine) {endpoint->
                assertEquals(503,endpoint.get("/health").statusCode())
                assertEquals("engine starting",endpoint.get("/health").body())
                assertTrue("logh7_engine_up 0" in endpoint.get("/metrics").body())
            }
            engine.synchronizeTime()
            serve(engine,engine) {endpoint->
                assertEquals(200,endpoint.get("/health").statusCode())
                assertEquals("ok",endpoint.get("/health").body())
                assertTrue("logh7_engine_up 1" in endpoint.get("/metrics").body())
            }
            val published=engine.snapshot()
            assertEquals(1049,published.navigators.getValue("pilot").baseId)
            assertEquals(Allegiance.Country(CountryId(2)),published.planetStocks!!.bases.getValue(1049).owner)
            assertEquals(52000L,published.planetStocks!!.bases.getValue(1049).treasury)
            assertEquals(450000L,published.planetStocks!!.nationalTreasuries.getValue(CountryId(2)))
        } finally {engine.drainAndClose();scope.cancel()}
    }

    @Test fun healthSourceExceptionRetainsDefault500RatherThanReportingHealthy() {
        val source = object : SnapshotSource {
            override fun snapshot() = snapshot
            override fun health(): EngineHealth = throw IllegalStateException("Injected health failure")
        }
        serve(source) { endpoint -> assertEquals(500, endpoint.get("/health").statusCode()) }
    }
}
