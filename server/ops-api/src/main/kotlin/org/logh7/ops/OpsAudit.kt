package org.logh7.ops

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Clock
import java.util.UUID

enum class OpsAction { PROBE, SESSION_RESTART }
enum class OpsOutcome { QUEUED, RESTARTED, CONFLICT, FAILED }

/** Append-only JSON Lines. Each line is fsync'd before the call returns. */
class OpsAudit private constructor(private val channel: FileChannel, private val clock: Clock) : AutoCloseable {
    companion object {
        internal val operatorPattern = Regex("[A-Za-z0-9._-]{1,64}")
        private val remotePattern = Regex("[0-9A-Fa-f:.]{1,45}")

        fun open(path: Path, clock: Clock = Clock.systemUTC()): OpsAudit =
            OpsAudit(FileChannel.open(path, CREATE, WRITE, APPEND), clock)
    }

    fun requested(action: OpsAction, operator: String, remote: String): String {
        require(operatorPattern.matches(operator))
        val requestId = UUID.randomUUID().toString()
        val address = remote.takeIf(remotePattern::matches) ?: "invalid"
        append("\"requestId\":\"$requestId\",\"phase\":\"REQUESTED\",\"action\":\"$action\",\"operator\":\"$operator\",\"remote\":\"$address\"")
        return requestId
    }

    fun result(requestId: String, action: OpsAction, operator: String, outcome: OpsOutcome) {
        require(UUID.fromString(requestId).toString() == requestId)
        require(operatorPattern.matches(operator))
        append("\"requestId\":\"$requestId\",\"phase\":\"RESULT\",\"action\":\"$action\",\"operator\":\"$operator\",\"outcome\":\"$outcome\"")
    }

    @Synchronized private fun append(fields: String) {
        val bytes = ByteBuffer.wrap("{\"at\":\"${clock.instant()}\",$fields}\n".toByteArray(Charsets.UTF_8))
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }

    @Synchronized override fun close() { channel.close() }
}
