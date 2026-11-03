package org.logh7.ops

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.logh7.engine.*
import kotlinx.coroutines.CompletableDeferred
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Operator headers are self-reported, not authentication. Loopback binding is the only access control. */
fun startOpsApi(commands: CommandSink, snapshots: SnapshotSource, port: Int = 47901,
    audit: OpsAudit? = null): AutoCloseable {
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
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

private suspend fun ApplicationCall.audited(audit: OpsAudit?, action: OpsAction,
    operation: suspend () -> OpsOutcome) {
    if (audit == null) {
        respondText("ops audit log is not configured", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    val operator = request.headers["X-LOGH7-Operator"]
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

/** Read-only operator page (loopback only, like every ops route). Commands stay on audited POST routes. */
internal fun opsAdminPage(health: EngineHealth, snapshot: WorldSnapshot): String = buildString {
    fun esc(value: Any?) = value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun row(vararg cells: Any?) { append("<tr>"); cells.forEach { append("<td>").append(esc(it)).append("</td>") }; append("</tr>") }
    val session = snapshot.session
    append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"10\">")
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
    append("</tbody></table><p>읽기 전용. 명령은 감사 기록이 남는 POST 경로로만 보낸다. 10초마다 새로 고침.</p></body></html>")
}
