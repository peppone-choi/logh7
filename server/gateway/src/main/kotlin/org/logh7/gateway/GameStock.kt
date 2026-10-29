package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameStock {
    fun snapshot(generation: Long): CompletableFuture<PlanetStocks>
    fun observeCivic(observer:(CivicChange)->Unit):AutoCloseable = AutoCloseable {}
}
class EngineGameStock(private val engine: WorldEngine, private val scope: CoroutineScope) : GameStock {
    override fun observeCivic(observer:(CivicChange)->Unit)=engine.observeCivic(observer)
    override fun snapshot(generation: Long): CompletableFuture<PlanetStocks> {
        val future = CompletableFuture<PlanetStocks>()
        scope.launch {
            try {
                val result = CompletableDeferred<PlanetStocks>()
                engine.submit(WorldCommand.Stock(generation, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
