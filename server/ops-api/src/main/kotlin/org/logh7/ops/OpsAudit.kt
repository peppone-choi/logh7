package org.logh7.ops

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Clock
import java.util.UUID

enum class OpsAction { PROBE, SESSION_RESTART, ACCOUNT_SET, ACCOUNT_REMOVE, BACKUP, WORLD_BASE_EDIT, COUNTRY_COLOR_EDIT, COUNTRY_DEFINITION_EDIT, MAINTENANCE, NOTICE, FACILITY_DEFINITION_EDIT, IMAGE_EDIT, IMAGE_RESTORE, IMAGE_BIND }
enum class OpsOutcome { QUEUED, RESTARTED, CONFLICT, FAILED, CHANGED, NOT_FOUND, INVALID, UNCHANGED, UNAVAILABLE }

/** Append-only JSON Lines. Each line is fsync'd before the call returns. */
class OpsAudit private constructor(private val channel: FileChannel, private val clock: Clock) : AutoCloseable {
    companion object {
        internal val operatorPattern = Regex("[A-Za-z0-9._-]{1,64}")
        private val remotePattern = Regex("[0-9A-Fa-f:.]{1,45}")

        fun open(path: Path, clock: Clock = Clock.systemUTC()): OpsAudit =
            OpsAudit(FileChannel.open(path, CREATE, WRITE, APPEND), clock)
    }

    fun requested(action: OpsAction, operator: String, remote: String, details:String?=null): String {
        require(operatorPattern.matches(operator))
        require(details==null || details.length<=4096 && details.none {it.isSurrogate()})
        val requestId = UUID.randomUUID().toString()
        val address = remote.takeIf(remotePattern::matches) ?: "invalid"
        val context=details?.let {text->",\"details\":"+buildString {
            append('"')
            for(c in text)when(c) {
                '"'->append("\\\"")
                '\\'->append("\\\\")
                in '\u0000'..'\u001f'->append("\\u%04x".format(c.code))
                else->append(c)
            }
            append('"')
        }}.orEmpty()
        append("\"requestId\":\"$requestId\",\"phase\":\"REQUESTED\",\"action\":\"$action\",\"operator\":\"$operator\",\"remote\":\"$address\"$context")
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
