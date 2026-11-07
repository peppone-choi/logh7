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
            // A requested row may carry optional ASCII details (maintenance mode, notice length/digest).
            assertEquals(common + (if (row["phase"] == "REQUESTED") setOf("remote") + row.keys.intersect(setOf("details")) else setOf("outcome")), row.keys)
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

    @Test fun tokenGuardsEveryRouteButHealthAndAcceptsBearerOrBasic() {
        val sink = Sink();val token = "0123456789abcdef-secret"
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OpsAudit.open(file, clock).use { audit -> startOpsApi(sink, snapshots, port, audit, token).use {
            HttpClient.newHttpClient().use { client ->
                fun get(path: String, auth: String? = null): HttpResponse<String> {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
                    if (auth != null) builder.header("Authorization", auth)
                    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
                }
                assertEquals(200, get("/health").statusCode())
                for (path in listOf("/admin", "/metrics", "/snapshot")) {
                    val refused = get(path)
                    assertEquals(401, refused.statusCode(), path)
                    assertTrue(refused.headers().firstValue("WWW-Authenticate").get().startsWith("Basic"))
                    assertEquals(401, get(path, "Bearer wrong-token-wrong-token").statusCode())
                }
                assertEquals(200, get("/admin", "Bearer $token").statusCode())
                val basic = java.util.Base64.getEncoder().encodeToString("ops:$token".toByteArray())
                assertEquals(200, get("/metrics", "Basic $basic").statusCode())
                val post = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/commands/probe"))
                    .header("X-LOGH7-Operator", "op1").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(401, post.statusCode());assertTrue(sink.commands.isEmpty())
            }
        } }
        assertFailsWith<IllegalArgumentException> { startOpsApi(sink, snapshots, port, null, "short") }
        assertFailsWith<IllegalArgumentException> { startOpsApi(sink, snapshots, port, null, null, "0.0.0.0") }
    }

    @Test fun adminFormButtonsAreAuditedAndRefuseCrossOriginPosts() {
        val sink = Sink()
        OpsAudit.open(file, clock).use { audit -> serve(sink, audit) { endpoint ->
            HttpClient.newHttpClient().use { client ->
                fun form(path: String, origin: String?): HttpResponse<String> {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${endpoint.port}$path"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                    if (origin != null) builder.header("Origin", origin)
                    return client.send(builder.POST(HttpRequest.BodyPublishers.ofString("operator=op1")).build(), HttpResponse.BodyHandlers.ofString())
                }
                assertEquals(403, form("/admin/probe", "http://evil.example").statusCode());assertTrue(sink.commands.isEmpty())
                val ok = form("/admin/probe", "http://127.0.0.1:${endpoint.port}")
                assertEquals(200, ok.statusCode());assertEquals("queued", ok.body())
                assertEquals(listOf(WorldCommand.Probe), sink.commands)
                assertPair("PROBE", "QUEUED")
            }
        } }
        assertTrue("/admin/restart" in opsAdminPage(EngineHealth.RUNNING, snapshot))
    }

    @Test fun adminAccountFormsAreAuditedAndNeverShowPasswords() {
        val names = sortedMapOf("multi0" to "dummy")
        val admin = object : AccountAdmin {
            override fun names() = names.keys.toList()
            override fun set(account: String, password: String) = (account.isNotEmpty() && password.length in 1..10).also { if (it) names[account] = password }
            override fun remove(account: String) = names.remove(account) != null
        }
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OpsAudit.open(file, clock).use { audit -> startOpsApi(Sink(), snapshots, port, audit, accounts = admin).use {
            HttpClient.newHttpClient().use { client ->
                fun form(path: String, body: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals("changed", form("/admin/account/set", "operator=op1&account=pilot&password=pw12").body())
                assertEquals(400, form("/admin/account/set", "operator=op1&account=pilot&password=").statusCode())
                assertEquals(404, form("/admin/account/remove", "operator=op1&account=ghost").statusCode())
                assertEquals("changed", form("/admin/account/remove", "operator=op1&account=multi0").body())
                val page = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                assertTrue("pilot" in page && "pw12" !in page && "/admin/account/set" in page)
            }
        } }
        assertEquals(mapOf("pilot" to "pw12"), names.toMap())
        val outcomes = rows().filter { it["phase"] == "RESULT" }.map { it["action"] + ":" + it["outcome"] }
        assertEquals(listOf("ACCOUNT_SET:CHANGED", "ACCOUNT_SET:INVALID", "ACCOUNT_REMOVE:NOT_FOUND", "ACCOUNT_REMOVE:CHANGED"), outcomes)
    }

    @Test fun alertInputsExposeBackupScheduleFailuresAndStateDiskSpace() {
        val backups = object : StateBackups {
            override suspend fun take() = "x"
            override fun lastSuccess(): Long? = 1_793_000_000L
            override fun intervalSeconds() = 3600L
            override fun failures() = 2L
        }
        val text = opsMetrics(EngineHealth.RUNNING, snapshot, backups, file.parent)
        val values = text.lines().filter { it.startsWith("logh7_") }.associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("3600", values["logh7_backup_interval_seconds"]); assertEquals("2", values["logh7_backup_failures_total"])
        val free = values.getValue("logh7_state_disk_free_bytes").toLong(); val total = values.getValue("logh7_state_disk_total_bytes").toLong()
        assertTrue(free in 1..total, "free $free of $total")
        // Defaults keep older implementations valid: manual backups (interval 0) never trip the staleness alert.
        val manual = object : StateBackups { override suspend fun take() = "x"; override fun lastSuccess(): Long? = null }
        assertTrue("logh7_backup_interval_seconds 0" in opsMetrics(EngineHealth.RUNNING, snapshot, manual))
        assertFalse("state_disk" in opsMetrics(EngineHealth.RUNNING, snapshot, manual))
    }

    @Test fun gatewayOutcomeCountersAreExportedAsCountersAndMalformedNamesAreRefused() {
        val text = opsMetrics(EngineHealth.RUNNING, snapshot, gateway = linkedMapOf("login_rejected_total" to 3L, "update_failed_total" to 1L))
        assertTrue("# TYPE logh7_login_rejected_total counter\nlogh7_login_rejected_total 3\n" in text)
        assertTrue("logh7_update_failed_total 1" in text)
        assertFalse("login_rejected" in opsMetrics(EngineHealth.RUNNING, snapshot))
        assertFailsWith<IllegalArgumentException> { opsMetrics(EngineHealth.RUNNING, snapshot, gateway = mapOf("bad name" to 1L)) }
    }

    private class FakeMaintenance : MaintenanceControl {
        var closed = false
        val notices = mutableListOf<String>()
        override fun closed() = closed
        override fun setClosed(closed: Boolean): Boolean { val changed = this.closed != closed; this.closed = closed; return changed }
        override fun notice(text: String): Int { notices += text; return 2 }
        override fun refusedLogins() = 5L
    }

    @Test fun maintenanceAndNoticeRoutesAreAuditedAndValidated() {
        val control = FakeMaintenance()
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OpsAudit.open(file, clock).use { audit -> startOpsApi(Sink(), snapshots, port, audit, maintenance = control).use {
            HttpClient.newHttpClient().use { client ->
                fun post(path: String, body: String = "") = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("X-LOGH7-Operator", "op1").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals("changed", post("/commands/maintenance/on").body())
                assertTrue(control.closed)
                assertEquals("unchanged", post("/commands/maintenance/on").body())
                assertEquals(400, post("/commands/maintenance/maybe").statusCode())
                assertEquals(400, post("/commands/notice", "").statusCode())
                assertEquals(400, post("/commands/notice", "x".repeat(61)).statusCode())
                assertEquals("changed", post("/commands/notice", "  오늘 22시 점검합니다  ").body())
                val metrics = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                assertTrue("logh7_maintenance_closed 1" in metrics && "logh7_maintenance_refused_logins_total 5" in metrics)
                val page = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                assertTrue("차단 중" in page && "/admin/notice" in page)
                assertEquals("changed", post("/commands/maintenance/off").body())
                assertFalse(control.closed)
                // A browser-driven cross-origin POST is refused before anything runs (PR #283 review).
                for (path in listOf("/commands/maintenance/on", "/commands/notice", "/commands/probe", "/commands/backup", "/session/restart")) {
                    val foreign = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("X-LOGH7-Operator", "op1")
                        .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("공격")).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(403, foreign.statusCode(), path)
                }
                assertFalse(control.closed); assertEquals(1, control.notices.size)
                val same = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/commands/maintenance/on")).header("X-LOGH7-Operator", "op1")
                    .header("Origin", "http://127.0.0.1:$port").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals("changed", same.body(), "the operator page's own origin is allowed")
            }
        } }
        assertEquals(listOf("오늘 22시 점검합니다"), control.notices)
        assertEquals(listOf("MAINTENANCE:CHANGED", "MAINTENANCE:UNCHANGED", "MAINTENANCE:INVALID", "NOTICE:INVALID", "NOTICE:INVALID",
            "NOTICE:CHANGED", "MAINTENANCE:CHANGED", "MAINTENANCE:CHANGED"), rows().filter { it["phase"] == "RESULT" }.map { it["action"] + ":" + it["outcome"] })
    }
    @Test fun onlyAuthenticatedOperatorsWithAuditCanDisconnectTheSelectedConnection() {
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        val selected = Collections.synchronizedMap(mutableMapOf(old to "pilot"))
        val control = object : PlayerConnectionAdmin {
            override val sessionLabel = "Alpha <1>"
            override fun current(): Map<String, String> = synchronized(selected) { selected.toMap() }
            override suspend fun disconnect(id: String) = selected.remove(id) != null
        }
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val token = "synthetic-ops-token"
        HttpClient.newHttpClient().use { client ->
            fun request(id: String? = null, authenticated: Boolean = true,
                origin: String = "http://127.0.0.1:$port"): HttpResponse<String> {
                val path = if (id == null) "/admin" else "/admin/player/disconnect"
                val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
                if (authenticated) builder.header("Authorization", "Bearer $token")
                if (id != null) builder.header("Origin", origin).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("operator=op1&connection=$id"))
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            OpsAudit.open(file, clock).use { audit ->
                startOpsApi(Sink(), snapshots, port, audit, token = token, playerConnections = control).use {
                    assertEquals(401, request(authenticated = false).statusCode())
                    assertEquals(401, request(old, authenticated = false).statusCode())
                    assertEquals(403, request(old, origin = "https://other.test").statusCode())
                    val page = request().body()
                    assertTrue("<strong>Alpha &lt;1&gt;</strong>" in page)
                    assertTrue("<option value=\"$old\">pilot</option>" in page)
                    assertEquals(400, request("invalid").statusCode())
                    assertEquals("선택한 접속을 종료했습니다.", request(old).body())
                    selected[fresh] = "pilot"
                    assertEquals(404, request(old).statusCode())
                    assertEquals(mapOf(fresh to "pilot"), control.current())
                }
                startOpsApi(Sink(), snapshots, port, audit, playerConnections = control).use {
                    assertFalse("/admin/player/disconnect" in request(authenticated = false).body())
                    assertEquals(503, request(fresh, authenticated = false).statusCode())
                    assertEquals(mapOf(fresh to "pilot"), control.current())
                }
            }
            startOpsApi(Sink(), snapshots, port, token = token, playerConnections = control).use {
                assertFalse("/admin/player/disconnect" in request().body())
                assertEquals(503, request(fresh).statusCode())
                assertEquals(mapOf(fresh to "pilot"), control.current())
            }
        }
        val auditRows = rows()
        assertEquals(listOf("INVALID", "CHANGED", "NOT_FOUND"), auditRows.mapNotNull { it["outcome"] })
        assertEquals(setOf("PLAYER_DISCONNECT"), auditRows.map { it["action"] }.toSet())
        assertEquals("pilot:$old", auditRows.single { it["details"]?.startsWith("pilot:") == true }["details"])
    }

    @Test fun backupRoutesAreAuditedAndReportTheLastSuccess() {
        var taken = 0
        val backups = object : StateBackups {
            override suspend fun take(): String { if (taken == 1) error("disk full"); taken++; return "state-20261104-120000" }
            override fun lastSuccess(): Long? = if (taken > 0) 1_793_000_000L else null
        }
        assertTrue("logh7_backup_last_success_seconds 0" in opsMetrics(EngineHealth.RUNNING, snapshot, backups))
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OpsAudit.open(file, clock).use { audit -> startOpsApi(Sink(), snapshots, port, audit, backups = backups).use {
            HttpClient.newHttpClient().use { client ->
                fun form() = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/backup"))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("operator=op1")).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals("changed", form().body())
                assertEquals(500, form().statusCode())
                val page = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                assertTrue("/admin/backup" in page && "2026-10-26T" in page)
            }
        } }
        assertTrue("logh7_backup_last_success_seconds 1793000000" in opsMetrics(EngineHealth.RUNNING, snapshot, backups))
        assertTrue("/admin/backup" !in opsAdminPage(EngineHealth.RUNNING, snapshot))
        assertEquals(listOf("BACKUP:CHANGED", "BACKUP:FAILED"), rows().filter { it["phase"] == "RESULT" }.map { it["action"] + ":" + it["outcome"] })
    }

    @Test fun namedOperatorLoginBecomesTheAuditOperator() {
        // Written by tools/ops/ops_user.py entry("operator-pass1", 1000, b"A"*16): Python and Kotlin must agree.
        val users = directory.resolve("ops-users.properties")
        Files.writeString(users, "alice=pbkdf2-sha256\\:1000\\:QUFBQUFBQUFBQUFBQUFBQQ==\\:Ab+PsEbuYY5gqqi/zTGWoVe2/14/f98p/qvoQSF3qVU=\n")
        val sink = Sink();val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        OpsAudit.open(file, clock).use { audit -> startOpsApi(sink, snapshots, port, audit, users = OpsUsers(users)).use {
            HttpClient.newHttpClient().use { client ->
                fun basic(pair: String) = "Basic " + java.util.Base64.getEncoder().encodeToString(pair.toByteArray())
                fun probe(auth: String?): HttpResponse<String> {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/commands/probe")).header("X-LOGH7-Operator", "mallory")
                    if (auth != null) builder.header("Authorization", auth)
                    return client.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                }
                assertEquals(401, probe(null).statusCode())
                assertEquals(401, probe(basic("alice:wrong")).statusCode())
                assertEquals(200, probe(basic("alice:operator-pass1")).statusCode())
            }
        } }
        assertEquals(listOf(WorldCommand.Probe), sink.commands)
        assertTrue(rows().all { it["operator"] == "alice" }, "the login, not the self-reported header, is recorded")
        assertFailsWith<IllegalArgumentException> { startOpsApi(sink, snapshots, port, null, null, "0.0.0.0") }
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
