package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

interface GameAdmission {
    val generation: Long
    val running: Boolean
    fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission>
    fun disconnect(account: String, connection: String)
}

/** Never block a Netty event loop while the world actor decides admission. */
class EngineGameAdmission(private val engine: WorldEngine, private val scope: CoroutineScope) : GameAdmission {
    private val requests = ConcurrentHashMap<String, CompletableFuture<Admission>>()
    /** Includes live and pending connection bindings; closed bindings must be removed. */
    fun trackedConnections(): Int = requests.size
    override val generation get() = checkNotNull(engine.snapshot().session).generation
    override val running get() = checkNotNull(engine.snapshot().session).ended == null
    override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission> {
        val future = CompletableFuture<Admission>()
        check(requests.putIfAbsent(connection, future) == null)
        scope.launch {
            try {
                val result = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account, if (character.power == 2) Power.EMPIRE else Power.ALLIANCE,
                    !character.generated, result, connection, generation))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun disconnect(account: String, connection: String) {
        // An early socket close must follow the queued join, rather than creating a ghost online member.
        requests.remove(connection)?.whenComplete { _, _ ->
            scope.launch { engine.submit(WorldCommand.Disconnect(account, connection)) }
        }
    }
}
