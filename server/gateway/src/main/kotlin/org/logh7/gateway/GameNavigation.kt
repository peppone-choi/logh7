package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameNavigation {
    fun apply(request: NavigationRequest): CompletableFuture<NavigationResult>
}

class EngineGameNavigation(private val engine: WorldEngine, private val scope: CoroutineScope) : GameNavigation {
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
