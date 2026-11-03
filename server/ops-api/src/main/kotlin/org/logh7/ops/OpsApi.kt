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

/** Operator names are self-reported. Loopback binding is the default access control; with [token] every route but
 * /health also needs `Authorization: Bearer <token>` or HTTP Basic with the token as password (browsers prompt). */
fun startOpsApi(commands: CommandSink, snapshots: SnapshotSource, port: Int = 47901,
    audit: OpsAudit? = null, token: String? = null, host: String = "127.0.0.1"): AutoCloseable {
    require(token == null || token.length >= 16) { "ops token must be at least 16 characters" }
    // A collector in another container needs a non-loopback bind; that is only allowed behind the token.
    require(token != null || java.net.InetAddress.getByName(host).isLoopbackAddress) { "a non-loopback ops bind requires LOGH7_OPS_TOKEN" }
    val server = embeddedServer(Netty, host = host, port = port) {
        intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
            if (token != null && call.request.local.uri.substringBefore('?') != "/health" && !opsAuthorized(call.request.headers["Authorization"], token)) {
                call.response.headers.append("WWW-Authenticate", "Basic realm=\"LOGH7 ops\", charset=\"UTF-8\"")
                call.respondText("ops token required", status = HttpStatusCode.Unauthorized)
                finish()
            }
        }
        routing {
            get("/health") {
                when (snapshots.health()) {
                    EngineHealth.RUNNING -> call.respondText("ok")
                    EngineHealth.STORAGE_FAILED -> call.respondText("engine storage failed", status = HttpStatusCode.ServiceUnavailable)
                    EngineHealth.STOPPED -> call.respondText("engine stopped", status = HttpStatusCode.ServiceUnavailable)
                }
            }
            get("/snapshot") { call.respondText(snapshots.snapshot().toString()) }
            get("/admin") {
                call.respondText(opsAdminPage(snapshots.health(), snapshots.snapshot()), io.ktor.http.ContentType.Text.Html)
            }
            get("/metrics") {
                call.respondText(opsMetrics(snapshots.health(), snapshots.snapshot()),
                    io.ktor.http.ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
            }
            // Admin page buttons: same audit as the header routes, operator from the form, same-origin only.
            post("/admin/probe") {
                call.adminForm(port)?.let { operator ->
                    call.audited(audit, OpsAction.PROBE, operator) { commands.submit(WorldCommand.Probe); OpsOutcome.QUEUED }
                }
            }
            post("/admin/restart") {
                call.adminForm(port)?.let { operator ->
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

/** Reads the operator field of an admin form; refuses a cross-origin post (a page elsewhere cannot drive the buttons). */
private suspend fun ApplicationCall.adminForm(port: Int): String? {
    val origin = request.headers["Origin"]
    if (origin != null && origin != "http://127.0.0.1:$port" && origin != "http://localhost:$port") {
        respondText("cross-origin admin request", status = HttpStatusCode.Forbidden)
        return null
    }
    return receiveParameters()["operator"].orEmpty()
}

private suspend fun ApplicationCall.audited(audit: OpsAudit?, action: OpsAction,
    operator: String? = request.headers["X-LOGH7-Operator"], operation: suspend () -> OpsOutcome) {
    if (audit == null) {
        respondText("ops audit log is not configured", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    if (operator == null || !OpsAudit.operatorPattern.matches(operator)) {
        respondText("invalid X-LOGH7-Operator", status = HttpStatusCode.BadRequest)
        return
    }
    val requestId = try { audit.requested(action, operator, request.origin.remoteAddress) }
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
        }
        respondText("$state; audit result not recorded", status = HttpStatusCode.InternalServerError)
        return
    }
    when (outcome) {
        OpsOutcome.QUEUED -> respondText("queued")
        OpsOutcome.RESTARTED -> respondText("restarted")
        OpsOutcome.CONFLICT -> respondText("session is running or restart failed", status = HttpStatusCode.Conflict)
        OpsOutcome.FAILED -> respondText("operation failed; outcome unknown", status = HttpStatusCode.InternalServerError)
    }
}

/** Prometheus text exposition of engine health and counters (deploy/prometheus scrapes this). */
internal fun opsMetrics(health: EngineHealth, snapshot: WorldSnapshot): String = buildString {
    fun metric(name: String, type: String, help: String, value: Number) {
        append("# HELP logh7_").append(name).append(' ').append(help).append('\n')
        append("# TYPE logh7_").append(name).append(' ').append(type).append('\n')
        append("logh7_").append(name).append(' ').append(value).append('\n')
    }
    val session = snapshot.session
    metric("engine_up", "gauge", "1 while the world engine runs and stores normally.", if (health == EngineHealth.RUNNING) 1 else 0)
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
}

/** Operator page (loopback, optional token). Its buttons post to the audited admin routes. */
internal fun opsAdminPage(health: EngineHealth, snapshot: WorldSnapshot): String = buildString {
    fun esc(value: Any?) = value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun row(vararg cells: Any?) { append("<tr>"); cells.forEach { append("<td>").append(esc(it)).append("</td>") }; append("</tr>") }
    val session = snapshot.session
    append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
    append("<title>LOGH7 운영</title><style>body{font-family:sans-serif;margin:16px}table{border-collapse:collapse;margin-bottom:16px}")
    append("td,th{border:1px solid #999;padding:2px 8px;text-align:left}</style></head><body><h1>LOGH7 운영 상태</h1>")
    append("<table><tbody>")
    row("엔진", health.name)
    row("월드 리비전", snapshot.revision)
    row("명령 접수/거부", "${snapshot.acceptedCommands} / ${snapshot.rejectedCommands}")
    row("세션 세대", session?.generation ?: "-")
    row("게임 시각(초)", session?.gameSeconds ?: "-")
    row("참가/접속", if (session == null) "-" else "${session.participants} / ${session.online}")
    row("세션 종료", session?.ended ?: "진행 중")
    row("전술 인스턴스", session?.tacticalTicks?.size ?: 0)
    row("진행 중 지상전 거점", snapshot.planetStocks?.groundBattles?.keys?.sorted()?.joinToString(", ")?.ifEmpty { "없음" } ?: "-")
    append("</tbody></table><h2>인물</h2><table><thead><tr><th>계정</th><th>인물</th><th>세력</th><th>그리드</th><th>거점</th><th>전술</th><th>PCP/MCP</th></tr></thead><tbody>")
    for ((account, n) in snapshot.navigators.toSortedMap())
        row(account, n.characterId, n.power, n.grid, n.baseId, if (n.cpPaused) "전술" else "전략", "${n.pcp} / ${n.mcp}")
    append("</tbody></table><h2>조작</h2><p>조작은 감사 기록에 운영자 이름과 함께 남는다.</p>")
    for ((path, label) in listOf("/admin/probe" to "엔진 응답 확인(probe)", "/admin/restart" to "세션 재시작(종료된 세션만)"))
        append("<form method=\"post\" action=\"").append(path).append("\">운영자 <input name=\"operator\" pattern=\"[A-Za-z0-9._-]{1,64}\" required> ")
            .append("<button>").append(label).append("</button></form>")
    append("<p>최신 값은 새로 고침으로 다시 읽는다.</p></body></html>")
}
