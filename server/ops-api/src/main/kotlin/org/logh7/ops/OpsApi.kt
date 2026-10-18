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
            get("/health") { call.respondText("ok") }
            get("/snapshot") { call.respondText(snapshots.snapshot().toString()) }
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
    val requestId = try { audit.requested(action, operator, request.origin.remoteHost) }
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
