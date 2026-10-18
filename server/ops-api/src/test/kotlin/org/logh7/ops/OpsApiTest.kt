package org.logh7.ops

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class OpsApiTest {
    @TempDir lateinit var directory: Path
    private val at = Instant.parse("2026-10-19T00:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val snapshot = WorldSnapshot(7, 3)
    private val snapshots = object : SnapshotSource { override fun snapshot() = snapshot }
    private val paths = listOf("/commands/probe", "/session/restart")
    private val file get() = directory.resolve("audit.jsonl")

    private class Sink(private val handle: suspend (WorldCommand) -> Unit = {
        if (it is WorldCommand.RequestRestart) it.result.complete(true)
    }) : CommandSink {
        val commands = Collections.synchronizedList(mutableListOf<WorldCommand>())
        override suspend fun submit(command: WorldCommand) { commands.add(command); handle(command) }
    }

    private class Endpoint(val port: Int) : AutoCloseable {
        private val client = HttpClient.newHttpClient()
        fun request(path: String, operator: String? = "op1", method: String = "POST"): HttpRequest {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
            if (operator != null) builder.header("X-LOGH7-Operator", operator)
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }
        fun send(path: String, operator: String? = "op1", method: String = "POST") =
            client.send(request(path, operator, method), HttpResponse.BodyHandlers.ofString())
        fun async(path: String) = client.sendAsync(request(path), HttpResponse.BodyHandlers.ofString())
        override fun close() { client.close() }
    }

    private fun serve(sink: Sink, audit: OpsAudit?, test: (Endpoint) -> Unit) {
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        startOpsApi(sink, snapshots, port, audit).use { Endpoint(port).use(test) }
    }

    // The writer's schema has only restricted strings: reject anything outside that JSON subset,
    // including duplicate keys, trailing garbage, escapes and unquoted values.
    private fun rows(): List<Map<String, String>> {
        val text = Files.readString(file)
        if (text.isEmpty()) return emptyList()
        assertTrue(text.endsWith("\n"))
        val field = Regex("\"([A-Za-z]+)\":\"([A-Za-z0-9._:+-]+)\"")
        return text.dropLast(1).split('\n').map { line ->
            assertTrue(line.startsWith('{') && line.endsWith('}'))
            val fields = line.substring(1, line.length - 1).split(',')
            val pairs = fields.map { part ->
                val match = assertNotNull(field.matchEntire(part), line)
                match.groupValues[1] to match.groupValues[2]
            }
            val row = pairs.toMap()
            assertEquals(pairs.size, row.size)
            val common = setOf("at", "requestId", "phase", "action", "operator")
            assertEquals(common + if (row["phase"] == "REQUESTED") "remote" else "outcome", row.keys)
            assertEquals(at, Instant.parse(row["at"]))
            assertEquals(row["requestId"], UUID.fromString(row["requestId"]).toString())
            row
        }
    }

    private fun assertPair(action: String, outcome: String) {
        val rows = rows()
        assertEquals(2, rows.size)
        assertEquals(listOf("REQUESTED", "RESULT"), rows.map { it["phase"] })
        assertEquals(rows[0]["requestId"], rows[1]["requestId"])
        rows.forEach { assertEquals(action, it["action"]); assertEquals("op1", it["operator"]) }
        assertEquals("127.0.0.1", rows[0]["remote"])
        assertEquals(outcome, rows[1]["outcome"])
    }

    @Test fun probeIsDurablyAuditedWithExactPhaseSchemas() {
        val sink = Sink { assertEquals("REQUESTED", rows().single()["phase"]) }
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            val response = endpoint.send(paths[0])
            assertEquals(200, response.statusCode()); assertEquals("queued", response.body())
            assertEquals(listOf(WorldCommand.Probe), sink.commands)
            assertPair("PROBE", "QUEUED")
        } }
    }

    @Test fun restartSuccess() {
        val sink = Sink()
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            val response = endpoint.send(paths[1])
            assertEquals(200, response.statusCode()); assertEquals("restarted", response.body())
            assertIs<WorldCommand.RequestRestart>(sink.commands.single())
            assertPair("SESSION_RESTART", "RESTARTED")
        } }
    }

    @Test fun restartConflict() {
        val sink = Sink { (it as WorldCommand.RequestRestart).result.complete(false) }
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            val response = endpoint.send(paths[1])
            assertEquals(409, response.statusCode())
            assertEquals("session is running or restart failed", response.body())
            assertPair("SESSION_RESTART", "CONFLICT")
        } }
    }

    @Test fun invalidOperatorsNeverReachQueueOrAudit() {
        val sink = Sink()
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            for (path in paths) for (operator in listOf(null, "a b", "a".repeat(65))) {
                assertEquals(400, endpoint.send(path, operator).statusCode())
            }
            assertTrue(sink.commands.isEmpty()); assertTrue(rows().isEmpty())
        } }
    }

    @Test fun missingAuditFailsClosedBeforeHeaderValidation() {
        val sink = Sink()
        serve(sink, null) { endpoint ->
            for (path in paths) for (operator in listOf(null, "op1")) {
                val response = endpoint.send(path, operator)
                assertEquals(503, response.statusCode()); assertEquals("ops audit log is not configured", response.body())
            }
        }
        assertTrue(sink.commands.isEmpty())
    }

    @Test fun requestedWriteFailureFailsClosed() {
        val sink = Sink()
        val audit = OpsAudit.open(file, clock)
        audit.close() // Open succeeded; exercise actual append IOException, not open failure.
        serve(sink, audit) { endpoint ->
            paths.forEach { assertEquals(503, endpoint.send(it).statusCode()) }
        }
        assertTrue(sink.commands.isEmpty()); assertTrue(rows().isEmpty())
    }

    @Test fun submitFailureIsAuditedAsFailed() {
        val sink = Sink { throw IOException("queue closed") }
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            val response = endpoint.send(paths[0])
            assertEquals(500, response.statusCode()); assertEquals("operation failed; outcome unknown", response.body())
            assertPair("PROBE", "FAILED")
        } }
    }

    @Test fun awaitFailureIsAuditedAsFailed() {
        val sink = Sink { (it as WorldCommand.RequestRestart).result.completeExceptionally(IOException("engine failed")) }
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            assertEquals(500, endpoint.send(paths[1]).statusCode())
            assertPair("SESSION_RESTART", "FAILED")
        } }
    }

    @Test fun resultWriteFailuresReportActualStageWithoutRetry() {
        for ((index, stage) in listOf("queued", "restarted", "conflict", "failed; outcome unknown").withIndex()) {
            Files.deleteIfExists(file)
            OpsAudit.open(file, clock).use { audit ->
                val sink = Sink { command ->
                    audit.close()
                    when (index) {
                        1 -> (command as WorldCommand.RequestRestart).result.complete(true)
                        2 -> (command as WorldCommand.RequestRestart).result.complete(false)
                        3 -> throw IOException("unknown submission outcome")
                    }
                }
                serve(sink, audit) { endpoint ->
                    val response = endpoint.send(if (index in 1..2) paths[1] else paths[0])
                    assertEquals(500, response.statusCode())
                    assertEquals("operation $stage; audit result not recorded", response.body())
                    assertEquals(1, sink.commands.size)
                    assertEquals("REQUESTED", rows().single()["phase"])
                }
            }
        }
    }

    @Test fun getEndpointsRemainUnchangedWithAndWithoutAudit() {
        OpsAudit.open(file, clock).use { audit ->
            for (configured in listOf(null, audit)) serve(Sink(), configured) { endpoint ->
                val health = endpoint.send("/health", null, "GET")
                assertEquals(200, health.statusCode()); assertEquals("ok", health.body())
                val state = endpoint.send("/snapshot", null, "GET")
                assertEquals(200, state.statusCode()); assertEquals(snapshot.toString(), state.body())
            }
            assertTrue(rows().isEmpty())
        }
    }

    @Test fun existingFileIsNotTruncated() {
        val previous = "{\"previous\":\"record\"}\n"
        Files.writeString(file, previous)
        OpsAudit.open(file, clock).use { audit -> serve(Sink(), audit) { endpoint ->
            assertEquals(200, endpoint.send(paths[0]).statusCode())
        } }
        val text = Files.readString(file)
        assertTrue(text.startsWith(previous)); assertEquals(3, text.lines().dropLast(1).size)
    }

    @Test fun concurrentRequestsProduceCompleteCorrelatedLines() {
        val sink = Sink()
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            val requests = List(20) { endpoint.async(paths[0]) }
            CompletableFuture.allOf(*requests.toTypedArray()).join()
            requests.forEach { assertEquals(200, it.join().statusCode()) }
            val rows = rows()
            assertEquals(20, sink.commands.size); assertEquals(40, rows.size)
            val groups = rows.groupBy { it["requestId"] }
            assertEquals(20, groups.size)
            groups.values.forEach { pair ->
                assertEquals(listOf("REQUESTED", "RESULT"), pair.map { it["phase"] })
                assertEquals("QUEUED", pair[1]["outcome"])
                pair.forEach { assertEquals("PROBE", it["action"]); assertEquals("op1", it["operator"]) }
            }
        } }
    }

    @Test fun unsafeRemoteIsRecordedAsInvalid() {
        OpsAudit.open(file, clock).use { audit -> audit.requested(OpsAction.PROBE, "op1", "bad\"\naddress") }
        assertEquals("invalid", rows().single()["remote"])
    }
}
