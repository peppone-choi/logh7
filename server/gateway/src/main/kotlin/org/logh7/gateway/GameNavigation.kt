package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameNavigation {
    fun apply(request: NavigationRequest): CompletableFuture<NavigationResult>
    fun snapshot(): WorldSnapshot? = null
    fun observeCombat(observer: (TacticalImpact) -> Unit): AutoCloseable = AutoCloseable {}
    fun observeMovements(observer:(TacticalMovement)->Unit):AutoCloseable = AutoCloseable {}
}

class EngineGameNavigation(private val engine: WorldEngine, private val scope: CoroutineScope) : GameNavigation {
    override fun observeCombat(observer: (TacticalImpact) -> Unit) = engine.observeCombat(observer)
    override fun observeMovements(observer:(TacticalMovement)->Unit) = engine.observeMovements(observer)
    override fun snapshot() = engine.snapshot()
    override fun apply(request: NavigationRequest): CompletableFuture<NavigationResult> {
        val future = CompletableFuture<NavigationResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<NavigationResult>()
                engine.submit(WorldCommand.Navigate(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
