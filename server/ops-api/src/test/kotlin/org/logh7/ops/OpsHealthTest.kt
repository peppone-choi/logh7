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
        for ((state, body) in listOf(EngineHealth.STORAGE_FAILED to "engine storage failed",
            EngineHealth.STOPPED to "engine stopped")) {
            serve(snapshots(state)) { endpoint ->
                val response = endpoint.get("/health")
                assertEquals(503, response.statusCode(), state.name)
                assertEquals(body, response.body())
            }
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

    @Test fun healthSourceExceptionRetainsDefault500RatherThanReportingHealthy() {
        val source = object : SnapshotSource {
            override fun snapshot() = snapshot
            override fun health(): EngineHealth = throw IllegalStateException("Injected health failure")
        }
        serve(source) { endpoint -> assertEquals(500, endpoint.get("/health").statusCode()) }
    }
}
