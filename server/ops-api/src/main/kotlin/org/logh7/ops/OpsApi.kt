package org.logh7.ops

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.logh7.engine.*
import kotlinx.coroutines.CompletableDeferred
import io.ktor.http.HttpStatusCode

fun startOpsApi(commands: CommandSink, snapshots: SnapshotSource, port: Int = 47901): AutoCloseable {
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        routing {
            get("/health") { call.respondText("ok") }
            get("/snapshot") { call.respondText(snapshots.snapshot().toString()) }
            post("/commands/probe") { commands.submit(WorldCommand.Probe); call.respondText("queued") }
            post("/session/restart") {
                val result = CompletableDeferred<Boolean>()
                commands.submit(WorldCommand.RequestRestart(result))
                if (result.await()) call.respondText("restarted")
                else call.respondText("session is running or restart failed", status = HttpStatusCode.Conflict)
            }
        }
    }.start(wait = false)
    return AutoCloseable { server.stop(500, 2000) }
}
