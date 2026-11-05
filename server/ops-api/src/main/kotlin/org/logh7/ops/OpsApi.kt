package org.logh7.ops

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.logh7.engine.*
import kotlinx.coroutines.CompletableDeferred
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import kotlinx.coroutines.CancellationException
import java.io.IOException
import io.ktor.server.request.receiveParameters
import java.security.MessageDigest
import java.util.Base64
import java.nio.file.Path

/** Operator names are self-reported. Loopback binding is the default access control; with [token] every route but
 * /health also needs `Authorization: Bearer <token>` or HTTP Basic with the token as password (browsers prompt). */
/** Login accounts the operator page manages (names only are ever shown). */
interface AccountAdmin {
    fun names(): List<String>
    /** Adds the account or resets its password; false when the name or password breaks the login rules. */
    fun set(account: String, password: String): Boolean
    fun remove(account: String): Boolean
}

/** Live state backups (LOGH7_BACKUP_DIR): on demand from the operator page and on the app's schedule. */
interface StateBackups {
    /** Takes one backup now; returns its directory name. */
    suspend fun take(): String
    /** Epoch seconds of the last successful backup, or null before the first. */
    fun lastSuccess(): Long?
    /** Scheduled period in seconds; 0 when backups are manual only (no staleness alert then). */
    fun intervalSeconds(): Long = 0
    /** Backups (scheduled or manual) that failed since start. */
    fun failures(): Long = 0
}

fun startOpsApi(commands: CommandSink, snapshots: SnapshotSource, port: Int = 47901,
    audit: OpsAudit? = null, token: String? = null, host: String = "127.0.0.1", accounts: AccountAdmin? = null,
    users: OpsUsers? = null, backups: StateBackups? = null, galaxy:Galaxy?=null,checklist:Path?=null,countryColors:CountryColorStore?=null,
    /** A path on the state volume (LOGH7_WORLD_STATE_DIR); its file store's free/total space is exported. */
    stateDisk: Path? = null,
    /** Gateway outcome counters (name without logh7_ -> value), e.g. login_rejected_total. */
    gatewayCounters: (() -> Map<String, Long>)? = null,
    /** World actor queue/tick timing histograms (the same object the engine records into). */
    actorTimings: ActorTimingHistograms? = null,
    /** Retention/capacity values (name without logh7_ -> value): *_total are counters, the rest gauges. */
    storage: (() -> Map<String, Long>)? = null): AutoCloseable {
    require(token == null || token.length >= 16) { "ops token must be at least 16 characters" }
    // A collector in another container needs a non-loopback bind; that is only allowed behind the token.
    require(token != null || users != null || java.net.InetAddress.getByName(host).isLoopbackAddress) {
        "a non-loopback ops bind requires LOGH7_OPS_TOKEN or LOGH7_OPS_USERS_FILE" }
    val server = embeddedServer(Netty, host = host, port = port) {
        intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
            if ((token != null || users != null) && call.request.local.uri.substringBefore('?') != "/health") {
                val header = call.request.headers["Authorization"]
                // A named operator login sets the audit operator; the shared token (machines) leaves it self-reported.
                val operator = users?.let { opsUser(header, it) }
                if (operator != null) call.attributes.put(opsOperatorKey, operator)
                else if (token == null || !opsAuthorized(header, token)) {
                    call.response.headers.append("WWW-Authenticate", "Basic realm=\"LOGH7 ops\", charset=\"UTF-8\"")
                    call.respondText("ops login required", status = HttpStatusCode.Unauthorized)
                    finish()
                }
            }
        }
        routing {
            worldEditorRoutes(commands,snapshots,port,audit,galaxy)
            countryColorRoutes(countryColors,port,audit)
            progressRoutes(checklist,"/admin/progress","/admin/progress/data")
            get("/health") {
                when (snapshots.health()) {
                    EngineHealth.STARTING -> call.respondText("engine starting", status = HttpStatusCode.ServiceUnavailable)
                    EngineHealth.RUNNING -> call.respondText("ok")
                    EngineHealth.STORAGE_FAILED -> call.respondText("engine storage failed", status = HttpStatusCode.ServiceUnavailable)
                    EngineHealth.STOPPED -> call.respondText("engine stopped", status = HttpStatusCode.ServiceUnavailable)
                }
            }
            get("/snapshot") { call.respondText(snapshots.snapshot().toString()) }
            get("/admin") {
                call.respondText(opsAdminPage(snapshots.health(), snapshots.snapshot(), accounts?.names(), backups), io.ktor.http.ContentType.Text.Html)
            }
            get("/admin/text") { call.respondRedirect("/admin/text/") }
            get("/admin/text/") {
                call.respondText(opsTextResource("editor.html"), io.ktor.http.ContentType.Text.Html)
            }
            get("/admin/text/{asset}") {
                val asset=call.parameters["asset"]
                if(asset !in setOf("editor.js","editor-ui.js")) call.respondText("not found",status=HttpStatusCode.NotFound)
                else call.respondText(opsTextResource(checkNotNull(asset)),io.ktor.http.ContentType.parse("text/javascript; charset=utf-8"))
            }
            get("/metrics") {
                call.respondText(opsMetrics(snapshots.health(), snapshots.snapshot(), backups, stateDisk, gatewayCounters?.invoke(), storage?.invoke()) +
                    (actorTimings?.exposition() ?: ""),
                    io.ktor.http.ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
            }
            // Admin page buttons: same audit as the header routes, operator from the form, same-origin only.
            post("/admin/account/set") {
                call.adminForm(port)?.let { form ->
                    call.audited(audit, OpsAction.ACCOUNT_SET, form["operator"].orEmpty()) {
                        val admin = accounts ?: return@audited OpsOutcome.FAILED
                        if (admin.set(form["account"].orEmpty(), form["password"].orEmpty())) OpsOutcome.CHANGED else OpsOutcome.INVALID
                    }
                }
            }
            post("/admin/account/remove") {
                call.adminForm(port)?.let { form ->
                    call.audited(audit, OpsAction.ACCOUNT_REMOVE, form["operator"].orEmpty()) {
                        val admin = accounts ?: return@audited OpsOutcome.FAILED
                        if (admin.remove(form["account"].orEmpty())) OpsOutcome.CHANGED else OpsOutcome.NOT_FOUND
                    }
                }
            }
            post("/admin/backup") {
                call.adminForm(port)?.get("operator")?.let { operator ->
                    call.audited(audit, OpsAction.BACKUP, operator) { backups?.take() ?: return@audited OpsOutcome.FAILED; OpsOutcome.CHANGED }
                }
            }
            post("/commands/backup") {
                call.audited(audit, OpsAction.BACKUP) { backups?.take() ?: return@audited OpsOutcome.FAILED; OpsOutcome.CHANGED }
            }
            post("/admin/probe") {
                call.adminForm(port)?.get("operator")?.let { operator ->
                    call.audited(audit, OpsAction.PROBE, operator) { commands.submit(WorldCommand.Probe); OpsOutcome.QUEUED }
                }
            }
            post("/admin/restart") {
                call.adminForm(port)?.get("operator")?.let { operator ->
                    call.audited(audit, OpsAction.SESSION_RESTART, operator) {
                        val result = CompletableDeferred<Boolean>()
                        commands.submit(WorldCommand.RequestRestart(result))
                        if (result.await()) OpsOutcome.RESTARTED else OpsOutcome.CONFLICT
                    }
                }
            }
            post("/commands/probe") {
                call.audited(audit, OpsAction.PROBE) {
                    commands.submit(WorldCommand.Probe)
                    OpsOutcome.QUEUED
                }
            }
            post("/session/restart") {
                call.audited(audit, OpsAction.SESSION_RESTART) {
                    val result = CompletableDeferred<Boolean>()
                    commands.submit(WorldCommand.RequestRestart(result))
                    if (result.await()) OpsOutcome.RESTARTED else OpsOutcome.CONFLICT
                }
            }
        }
    }
    try { server.start(wait = false) }
    catch (failure: Throwable) {
        try { server.stop(0, 2000) } catch (closing: Throwable) { failure.addSuppressed(closing) }
        throw failure
    }
    return AutoCloseable { server.stop(500, 2000) }
}

private val opsOperatorKey = io.ktor.util.AttributeKey<String>("logh7.opsOperator")

private fun opsTextResource(name:String):String = checkNotNull(AccountAdmin::class.java.getResourceAsStream("/admin/text/$name"))
    .bufferedReader(Charsets.UTF_8).use {it.readText()}

internal fun opsUser(header: String?, users: OpsUsers): String? {
    if (header == null || !header.startsWith("Basic ")) return null
    val pair = runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8) }.getOrNull() ?: return null
    val name = pair.substringBefore(':', "");val password = pair.substringAfter(':', "")
    return if (name.isEmpty()) null else users.authenticate(name, password)
}

internal fun opsAuthorized(header: String?, token: String): Boolean {
    val presented = when {
        header == null -> return false
        header.startsWith("Bearer ") -> header.removePrefix("Bearer ")
        header.startsWith("Basic ") -> runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8) }
            .getOrNull()?.substringAfter(':', "") ?: return false
        else -> return false
    }
    return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
}

/** Reads an admin form; refuses a cross-origin post (a page elsewhere cannot drive the buttons). */
internal suspend fun ApplicationCall.adminForm(port: Int): io.ktor.http.Parameters? {
    val origin = request.headers["Origin"]
    if (origin != null && origin != "http://127.0.0.1:$port" && origin != "http://localhost:$port") {
        respondText("cross-origin admin request", status = HttpStatusCode.Forbidden)
        return null
    }
    return receiveParameters()
}

internal suspend fun ApplicationCall.audited(audit: OpsAudit?, action: OpsAction,
    reported: String? = request.headers["X-LOGH7-Operator"],details:String?=null,messages:Map<OpsOutcome,String> = emptyMap(),
    operation: suspend () -> OpsOutcome) {
    val operator = attributes.getOrNull(opsOperatorKey) ?: reported
    if (audit == null) {
        respondText("ops audit log is not configured", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    if (operator == null || !OpsAudit.operatorPattern.matches(operator)) {
        respondText("invalid X-LOGH7-Operator", status = HttpStatusCode.BadRequest)
        return
    }
    val requestId = try { audit.requested(action, operator, request.origin.remoteAddress,details) }
    catch (_: IOException) {
        respondText("ops audit request not recorded", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    val outcome = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { OpsOutcome.FAILED }
    try { audit.result(requestId, action, operator, outcome) }
    catch (_: IOException) {
        val state = when (outcome) {
            OpsOutcome.QUEUED -> "operation queued"
            OpsOutcome.RESTARTED -> "operation restarted"
            OpsOutcome.CONFLICT -> "operation conflict"
            OpsOutcome.FAILED -> "operation failed; outcome unknown"
            OpsOutcome.CHANGED -> "operation changed"
            OpsOutcome.NOT_FOUND -> "operation found nothing"
            OpsOutcome.INVALID -> "operation refused as invalid"
            OpsOutcome.UNCHANGED -> "operation unchanged"
            OpsOutcome.UNAVAILABLE -> "operation unavailable"
        }
        respondText("$state; audit result not recorded", status = HttpStatusCode.InternalServerError)
        return
    }
    when (outcome) {
        OpsOutcome.QUEUED -> respondText("queued")
        OpsOutcome.RESTARTED -> respondText("restarted")
        OpsOutcome.CONFLICT -> respondText(messages[outcome] ?: "session is running or restart failed", status = HttpStatusCode.Conflict)
        OpsOutcome.FAILED -> respondText("operation failed; outcome unknown", status = HttpStatusCode.InternalServerError)
        OpsOutcome.CHANGED -> respondText("changed")
        OpsOutcome.NOT_FOUND -> respondText(messages[outcome] ?: "no such account", status = HttpStatusCode.NotFound)
        OpsOutcome.INVALID -> respondText(messages[outcome] ?: "account 1-30 of [A-Za-z0-9._-], password 1-10 printable ASCII", status = HttpStatusCode.BadRequest)
        OpsOutcome.UNCHANGED -> respondText("unchanged")
        OpsOutcome.UNAVAILABLE -> respondText("world editing unavailable",status=HttpStatusCode.ServiceUnavailable)
    }
}

/** Prometheus text exposition of engine health and counters (deploy/prometheus scrapes this). */
internal fun opsMetrics(health: EngineHealth, snapshot: WorldSnapshot, backups: StateBackups? = null,
    stateDisk: Path? = null, gateway: Map<String, Long>? = null, storage: Map<String, Long>? = null): String = buildString {
    fun metric(name: String, type: String, help: String, value: Number) {
        append("# HELP logh7_").append(name).append(' ').append(help).append('\n')
        append("# TYPE logh7_").append(name).append(' ').append(type).append('\n')
        append("logh7_").append(name).append(' ').append(value).append('\n')
    }
    val session = snapshot.session
    metric("engine_up", "gauge", "1 while the world engine runs and stores normally.", if (health == EngineHealth.RUNNING) 1 else 0)
    // One series per state, so alerts tell a storage failure from starting or stopped (engine_up is 0 for all three).
    append("# HELP logh7_engine_state 1 for the world engine's current state (starting, running, storage_failed, stopped).\n")
    append("# TYPE logh7_engine_state gauge\n")
    for (state in EngineHealth.entries)
        append("logh7_engine_state{state=\"").append(state.name.lowercase()).append("\"} ").append(if (state == health) 1 else 0).append('\n')
    metric("world_revision", "counter", "Committed world revisions.", snapshot.revision)
    metric("commands_accepted_total", "counter", "World commands accepted.", snapshot.acceptedCommands)
    metric("commands_rejected_total", "counter", "World commands rejected.", snapshot.rejectedCommands)
    metric("session_generation", "gauge", "Current session generation (0 without a session).", session?.generation ?: 0)
    metric("session_game_seconds", "gauge", "Game clock in seconds.", session?.gameSeconds ?: 0)
    metric("session_participants", "gauge", "Session participants.", session?.participants ?: 0)
    metric("session_online", "gauge", "Participants online now.", session?.online ?: 0)
    metric("session_ended", "gauge", "1 once the session has ended.", if (session?.ended != null) 1 else 0)
    metric("tactical_instances", "gauge", "Running tactical instances.", session?.tacticalTicks?.size ?: 0)
    metric("ground_battles", "gauge", "Bases with a running ground battle.", snapshot.planetStocks?.groundBattles?.size ?: 0)
    // Process health for long-running soak checks (item 269).
    val runtime = Runtime.getRuntime()
    metric("jvm_heap_used_bytes", "gauge", "JVM heap in use.", runtime.totalMemory() - runtime.freeMemory())
    metric("jvm_heap_max_bytes", "gauge", "JVM heap limit.", runtime.maxMemory())
    metric("jvm_threads", "gauge", "Live JVM threads.", Thread.activeCount())
    // Staleness alerts measure from the later of this and the last backup, so a fresh start does not page.
    metric("process_start_seconds", "gauge", "Epoch seconds when this server process started.",
        java.lang.management.ManagementFactory.getRuntimeMXBean().startTime / 1000)
    // RPO watch: alert when time() minus this grows past the backup interval.
    if (backups != null) {
        metric("backup_last_success_seconds", "gauge", "Epoch seconds of the last live backup (0 before the first).", backups.lastSuccess() ?: 0)
        metric("backup_interval_seconds", "gauge", "Scheduled live backup period (0 = manual only).", backups.intervalSeconds())
        metric("backup_failures_total", "counter", "Live backups that failed since start.", backups.failures())
    }
    // Repeated login/ticket/update/connection failures (counts only; no account names).
    gateway?.forEach { (name, value) ->
        require(Regex("[a-z_]+_total").matches(name)) { "gateway counter name: $name" }
        metric(name, "counter", "Gateway outcome count since start (${name.removeSuffix("_total").replace('_', ' ')}).", value)
    }
    // Retention/capacity (capture directory, audit log): *_total counters, everything else gauges.
    storage?.forEach { (name, value) ->
        require(Regex("[a-z_]+").matches(name)) { "storage metric name: $name" }
        metric(name, if (name.endsWith("_total")) "counter" else "gauge", "Retention and capacity (${name.replace('_', ' ')}).", value)
    }
    // Capacity alerts (deploy/prometheus/game-alerts.yml); an unreadable store just omits the pair.
    stateDisk?.let { path ->
        try {
            val store = java.nio.file.Files.getFileStore(path)
            metric("state_disk_free_bytes", "gauge", "Usable bytes on the state volume.", store.usableSpace)
            metric("state_disk_total_bytes", "gauge", "Total bytes on the state volume.", store.totalSpace)
        } catch (_: java.io.IOException) {}
    }
}

/** Operator page (loopback, optional token). Its buttons post to the audited admin routes. */
internal fun opsAdminPage(health: EngineHealth, snapshot: WorldSnapshot, accounts: List<String>? = null,
    backups: StateBackups? = null): String = buildString {
    fun esc(value: Any?) = value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun row(vararg cells: Any?) { append("<tr>"); cells.forEach { append("<td>").append(esc(it)).append("</td>") }; append("</tr>") }
    val session = snapshot.session
    append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
    append("<title>LOGH7 운영</title><style>body{font-family:sans-serif;margin:16px}table{border-collapse:collapse;margin-bottom:16px}")
    append("td,th{border:1px solid #999;padding:2px 8px;text-align:left}</style></head><body><h1>LOGH7 운영 상태</h1>")
    append("<nav><a href=\"/admin/progress\">작업 현황</a> · <a href=\"/admin/world\">세계 편집</a> · <a href=\"/admin/countries\">국가 색상</a> · <a href=\"/admin/text/\">문구 편집</a></nav><table><tbody>")
    row("엔진", health.name)
    row("월드 리비전", snapshot.revision)
    row("명령 접수/거부", "${snapshot.acceptedCommands} / ${snapshot.rejectedCommands}")
    row("세션 세대", session?.generation ?: "-")
    row("게임 시각(초)", session?.gameSeconds ?: "-")
    row("참가/접속", if (session == null) "-" else "${session.participants} / ${session.online}")
    row("세션 종료", session?.ended ?: "진행 중")
    row("전술 인스턴스", session?.tacticalTicks?.size ?: 0)
    row("진행 중 지상전 거점", snapshot.planetStocks?.groundBattles?.keys?.sorted()?.joinToString(", ")?.ifEmpty { "없음" } ?: "-")
    if (backups != null) row("마지막 백업", backups.lastSuccess()?.let { java.time.Instant.ofEpochSecond(it).toString() } ?: "없음")
    append("</tbody></table><h2>인물</h2><table><thead><tr><th>계정</th><th>인물</th><th>세력</th><th>그리드</th><th>거점</th><th>전술</th><th>PCP/MCP</th></tr></thead><tbody>")
    for ((account, n) in snapshot.navigators.toSortedMap())
        row(account, n.characterId, n.power, n.grid, n.baseId, if (n.cpPaused) "전술" else "전략", "${n.pcp} / ${n.mcp}")
    append("</tbody></table><h2>조작</h2><p>조작은 감사 기록에 운영자 이름과 함께 남는다.</p>")
    val actions = listOf("/admin/probe" to "엔진 응답 확인(probe)", "/admin/restart" to "세션 재시작(종료된 세션만)") +
        listOfNotNull(backups?.let { "/admin/backup" to "지금 백업" })
    for ((path, label) in actions)
        append("<form method=\"post\" action=\"").append(path).append("\">운영자 <input name=\"operator\" pattern=\"[A-Za-z0-9._-]{1,64}\" required> ")
            .append("<button>").append(label).append("</button></form>")
    if (accounts != null) {
        append("<h2>계정</h2><p>로그인 계정 ").append(accounts.size).append("개(비밀번호는 표시하지 않음): ")
            .append(esc(accounts.joinToString(", "))).append("</p>")
        val operator = "운영자 <input name=\"operator\" pattern=\"[A-Za-z0-9._-]{1,64}\" required> "
        append("<form method=\"post\" action=\"/admin/account/set\">").append(operator)
            .append("계정 <input name=\"account\" pattern=\"[A-Za-z0-9._-]{1,30}\" required> ")
            .append("비밀번호 <input name=\"password\" type=\"password\" maxlength=\"10\" required> <button>추가·비밀번호 변경</button></form>")
        append("<form method=\"post\" action=\"/admin/account/remove\">").append(operator)
            .append("계정 <input name=\"account\" required> <button>삭제</button></form>")
    }
    append("<p>최신 값은 새로 고침으로 다시 읽는다.</p></body></html>")
}
